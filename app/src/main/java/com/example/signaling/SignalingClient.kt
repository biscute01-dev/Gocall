package com.example.signaling

import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class SignalingClient(
    private var customDatabaseUrl: String? = null
) {
    companion object {
        private const val TAG = "SignalingClient"
        const val DEFAULT_FALLBACK_RTDB = "https://webrtc-video-calling-default-rtdb.firebaseio.com"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var currentRoomId: String? = null
    private var isBroadcaster: Boolean = false
    private var firebaseDb: FirebaseDatabase? = null
    private var roomRef: DatabaseReference? = null

    private var roomValueListener: ValueEventListener? = null
    private var candidatesListener: ChildEventListener? = null

    private var restPollingJob: Job? = null
    private val processedCandidateKeys = mutableSetOf<String>()

    // Deduplication guards so RTDB snapshots don't re-apply identical SDPs or commands
    private var lastAppliedAnswerSdp: String? = null
    private var lastAppliedOfferSessionKey: String? = null
    private var lastAppliedReconnectTs: Long = 0L
    private var lastProcessedCommandId: String? = null
    private var broadcastStartedAtMs: Long = 0L
    private var hasNotifiedViewerLeftForSession: Boolean = false
    private var lastStandbyNotified: Boolean = false

    init {
        initFirebaseDatabase()
    }

    fun updateCustomUrl(url: String?) {
        customDatabaseUrl = url
        initFirebaseDatabase()
    }

    private fun initFirebaseDatabase() {
        try {
            val url = customDatabaseUrl?.trim()?.takeIf { it.isNotBlank() }
            firebaseDb = if (url != null) {
                FirebaseDatabase.getInstance(url)
            } else {
                try {
                    FirebaseDatabase.getInstance()
                } catch (e: Exception) {
                    FirebaseDatabase.getInstance(DEFAULT_FALLBACK_RTDB)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseDatabase instance init note: ${e.message}. Using REST fallback if needed.")
        }
    }

    /**
     * Publishes a live surveillance broadcast offer on `rooms/{channelId}`.
     * Clears any previous viewer answer/candidates and stale commands so a fresh viewer can connect immediately.
     */
    fun startBroadcast(
        channelId: String,
        sdpOffer: String,
        sessionId: String,
        isFrontCamera: Boolean = false,
        isMicEnabled: Boolean = true
    ) {
        cleanupListenersOnly()
        this.currentRoomId = channelId
        this.isBroadcaster = true
        processedCandidateKeys.clear()
        lastAppliedAnswerSdp = null
        lastAppliedOfferSessionKey = null
        hasNotifiedViewerLeftForSession = false
        broadcastStartedAtMs = System.currentTimeMillis()

        val offerMap = mapOf(
            "sdp" to sdpOffer,
            "type" to "OFFER",
            "sessionId" to sessionId
        )
        val mediaMap = mapOf(
            "cameraEnabled" to true,
            "micEnabled" to isMicEnabled,
            "isFrontCamera" to isFrontCamera,
            "broadcastActive" to true
        )
        val roomMap = mapOf(
            "caller" to offerMap,
            "callerMedia" to mediaMap,
            "status" to "broadcasting",
            "sessionId" to sessionId,
            "createdAt" to broadcastStartedAtMs
        )

        val db = firebaseDb
        if (db != null) {
            try {
                val ref = db.getReference("rooms").child(channelId)
                roomRef = ref
                ref.setValue(roomMap).addOnSuccessListener {
                    Log.d(TAG, "Broadcast channel $channelId live on Firebase RTDB (session=$sessionId)")
                    listenAsBroadcaster(ref)
                    listenForRemoteCandidates(ref, childPath = "calleeCandidates", isCallerCandidate = false)
                }.addOnFailureListener { err ->
                    Log.w(TAG, "Firebase SDK setValue failed: ${err.message}. Falling back to REST...")
                    fallbackRestStartBroadcast(channelId, roomMap)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firebase error, using REST: ${e.message}")
                fallbackRestStartBroadcast(channelId, roomMap)
            }
        } else {
            fallbackRestStartBroadcast(channelId, roomMap)
        }
    }

    /**
     * Transitions the Broadcaster channel into Remote Standby:
     * removes active SDP/candidates and marks status = "standby", while keeping the Broadcaster
     * listener active so it can instantly execute a remote START_BROADCAST command from the Viewer.
     */
    fun enterStandbyMode(isFrontCamera: Boolean) {
        val channelId = currentRoomId ?: return
        processedCandidateKeys.clear()
        lastAppliedAnswerSdp = null
        hasNotifiedViewerLeftForSession = false

        val mediaMap = mapOf(
            "cameraEnabled" to false,
            "micEnabled" to false,
            "isFrontCamera" to isFrontCamera,
            "broadcastActive" to false
        )
        val standbyMap = mapOf(
            "status" to "standby",
            "callerMedia" to mediaMap,
            "updatedAt" to System.currentTimeMillis()
        )

        val ref = roomRef
        if (ref != null) {
            ref.setValue(standbyMap).addOnFailureListener { err ->
                Log.w(TAG, "Firebase enterStandbyMode failed: ${err.message}")
                scope.launch {
                    postRestData("rooms/$channelId", JSONObject(standbyMap).toString())
                }
            }
        } else {
            scope.launch {
                postRestData("rooms/$channelId", JSONObject(standbyMap).toString())
            }
        }
    }

    /**
     * Joins a surveillance channel as a Viewer (recvonly).
     * Automatically waits if the Broadcaster hasn't started yet and detects Standby or Live state immediately.
     */
    fun joinAsViewer(channelId: String) {
        cleanupListenersOnly()
        this.currentRoomId = channelId
        this.isBroadcaster = false
        processedCandidateKeys.clear()
        lastAppliedOfferSessionKey = null
        lastAppliedReconnectTs = 0L
        lastStandbyNotified = false

        val db = firebaseDb
        if (db != null) {
            try {
                val ref = db.getReference("rooms").child(channelId)
                roomRef = ref
                listenAsViewer(ref)
                listenForRemoteCandidates(ref, childPath = "callerCandidates", isCallerCandidate = true)
            } catch (e: Exception) {
                Log.w(TAG, "Firebase viewer error, using REST polling: ${e.message}")
                startRestPolling(channelId)
            }
        } else {
            startRestPolling(channelId)
        }
    }

    /**
     * Sends a remote control command from the Viewer to the Broadcaster.
     */
    fun sendRemoteCommand(action: RemoteCommandAction, value: Boolean = false) {
        val channelId = currentRoomId ?: return
        val cmdId = "cmd_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val cmdMap = mapOf(
            "id" to cmdId,
            "action" to action.name,
            "value" to value,
            "timestamp" to System.currentTimeMillis()
        )
        Log.d(TAG, "Sending remote command to '$channelId': $action (value=$value, id=$cmdId)")
        val ref = roomRef
        if (ref != null) {
            ref.child("remoteCommand").setValue(cmdMap)
        }
        scope.launch {
            postRestData("rooms/$channelId/remoteCommand", JSONObject(cmdMap).toString())
        }
    }

    fun sendAnswer(sdpAnswer: String) {
        val roomId = currentRoomId ?: return
        val answerMap = mapOf(
            "sdp" to sdpAnswer,
            "type" to "ANSWER",
            "timestamp" to System.currentTimeMillis()
        )

        val ref = roomRef
        if (ref != null) {
            ref.child("callee").setValue(answerMap)
            ref.child("status").setValue("connected")
        }
        scope.launch {
            postRestData("rooms/$roomId/callee", JSONObject(answerMap).toString())
            postRestData("rooms/$roomId/status", "\"connected\"")
        }
    }

    fun sendIceCandidate(candidate: IceCandidateModel) {
        val roomId = currentRoomId ?: return
        val candidateMap = mapOf(
            "sdp" to candidate.sdp,
            "sdpMid" to (candidate.sdpMid ?: ""),
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "serverUrl" to (candidate.serverUrl ?: "")
        )

        val targetPath = if (isBroadcaster) "callerCandidates" else "calleeCandidates"
        val ref = roomRef
        if (ref != null) {
            ref.child(targetPath).push().setValue(candidateMap)
        }
        scope.launch {
            postRestData("rooms/$roomId/$targetPath", JSONObject(candidateMap).toString(), isPush = true)
        }
    }

    fun sendReconnectOffer(sdpOffer: String, sessionId: String) {
        val roomId = currentRoomId ?: return
        val ts = System.currentTimeMillis()
        val offerMap = mapOf(
            "sdp" to sdpOffer,
            "type" to "OFFER",
            "sessionId" to sessionId,
            "reconnectTimestamp" to ts
        )
        val ref = roomRef
        if (ref != null) {
            ref.child("caller").setValue(offerMap)
            ref.child("status").setValue("reconnecting")
        }
        scope.launch {
            postRestData("rooms/$roomId/caller", JSONObject(offerMap).toString())
            postRestData("rooms/$roomId/status", "\"reconnecting\"")
        }
    }

    fun updateStatus(status: String) {
        val roomId = currentRoomId ?: return
        roomRef?.child("status")?.setValue(status)
        scope.launch {
            postRestData("rooms/$roomId/status", "\"$status\"")
        }
    }

    fun updateMediaState(
        isCameraEnabled: Boolean,
        isMicEnabled: Boolean,
        isFrontCamera: Boolean = false,
        broadcastActive: Boolean = true
    ) {
        val roomId = currentRoomId ?: return
        val targetPath = if (isBroadcaster) "callerMedia" else "calleeMedia"
        val stateMap = mapOf(
            "cameraEnabled" to isCameraEnabled,
            "micEnabled" to isMicEnabled,
            "isFrontCamera" to isFrontCamera,
            "broadcastActive" to broadcastActive
        )
        roomRef?.child(targetPath)?.setValue(stateMap)
        scope.launch {
            postRestData("rooms/$roomId/$targetPath", JSONObject(stateMap).toString())
        }
    }

    private fun handleIncomingCommand(id: String?, actionName: String?, value: Boolean, timestamp: Long) {
        if (id.isNullOrBlank() || actionName.isNullOrBlank()) return
        if (id == lastProcessedCommandId) return
        // Ignore stale commands left over from before this broadcaster session booted
        if (timestamp > 0L && timestamp < broadcastStartedAtMs - 15_000L) return

        val action = try {
            RemoteCommandAction.valueOf(actionName)
        } catch (_: Exception) {
            return
        }

        lastProcessedCommandId = id
        val cmd = RemoteCommand(id = id, action = action, value = value, timestamp = timestamp)
        Log.d(TAG, "Broadcaster received remote command: $cmd")
        scope.launch {
            _events.emit(SignalingEvent.RemoteCommandReceived(cmd))
        }
    }

    private fun listenAsBroadcaster(ref: DatabaseReference) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                // 1. Check incoming remote control command from Viewer
                val cmdSnap = snapshot.child("remoteCommand")
                if (cmdSnap.exists()) {
                    val cmdId = cmdSnap.child("id").getValue(String::class.java)
                    val actionName = cmdSnap.child("action").getValue(String::class.java)
                    val value = cmdSnap.child("value").getValue(Boolean::class.java) ?: false
                    val ts = cmdSnap.child("timestamp").getValue(Long::class.java) ?: 0L
                    handleIncomingCommand(cmdId, actionName, value, ts)
                }

                // 2. Check incoming viewer answer
                val calleeSnap = snapshot.child("callee")
                val answerSdp = calleeSnap.child("sdp").getValue(String::class.java)
                if (!answerSdp.isNullOrBlank() && answerSdp != lastAppliedAnswerSdp) {
                    lastAppliedAnswerSdp = answerSdp
                    scope.launch {
                        _events.emit(SignalingEvent.AnswerReceived(answerSdp))
                    }
                }

                // 3. Check channel status
                val status = snapshot.child("status").getValue(String::class.java)
                when (status) {
                    "viewer_left" -> {
                        if (!hasNotifiedViewerLeftForSession) {
                            hasNotifiedViewerLeftForSession = true
                            scope.launch {
                                _events.emit(SignalingEvent.ViewerLeft(System.currentTimeMillis()))
                            }
                        }
                    }
                    "reconnecting" -> {
                        scope.launch {
                            _events.emit(SignalingEvent.ReconnectRequested(System.currentTimeMillis()))
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "listenAsBroadcaster onCancelled: ${error.message}")
                currentRoomId?.let { startRestPolling(it) }
            }
        }
        roomValueListener = listener
        ref.addValueEventListener(listener)
    }

    private fun listenAsViewer(ref: DatabaseReference) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val status = snapshot.child("status").getValue(String::class.java) ?: ""
                val callerSnap = snapshot.child("caller")
                val offerSdp = callerSnap.child("sdp").getValue(String::class.java)
                val sessionId = callerSnap.child("sessionId").getValue(String::class.java) ?: ""
                val reconnectTs = callerSnap.child("reconnectTimestamp").getValue(Long::class.java) ?: 0L

                // Check broadcaster media state (camera paused / mic muted / front-or-rear / broadcastActive)
                val mediaSnap = snapshot.child("callerMedia")
                if (mediaSnap.exists()) {
                    val cameraEnabled = mediaSnap.child("cameraEnabled").getValue(Boolean::class.java) ?: true
                    val micEnabled = mediaSnap.child("micEnabled").getValue(Boolean::class.java) ?: true
                    val isFront = mediaSnap.child("isFrontCamera").getValue(Boolean::class.java) ?: false
                    val broadcastActive = mediaSnap.child("broadcastActive").getValue(Boolean::class.java) ?: (status != "standby")
                    scope.launch {
                        _events.emit(
                            SignalingEvent.PeerMediaStateChanged(
                                isCameraEnabled = cameraEnabled,
                                isMicEnabled = micEnabled,
                                isFrontCamera = isFront,
                                broadcastActive = broadcastActive
                            )
                        )
                    }
                }

                // Handle broadcaster in Remote Standby (armed & waiting for remote START_BROADCAST)
                if (status == "standby") {
                    lastAppliedOfferSessionKey = null
                    processedCandidateKeys.clear()
                    if (!lastStandbyNotified) {
                        lastStandbyNotified = true
                        scope.launch {
                            _events.emit(SignalingEvent.BroadcastStandby)
                        }
                    }
                    return
                } else {
                    lastStandbyNotified = false
                }

                // Handle broadcaster completely exited
                if (status == "ended" && lastAppliedOfferSessionKey != null) {
                    lastAppliedOfferSessionKey = null
                    processedCandidateKeys.clear()
                    scope.launch {
                        _events.emit(SignalingEvent.PeerDisconnected("Broadcast stopped by camera"))
                    }
                    return
                }

                // Pick up fresh broadcast offer OR reconnect offer
                if (!offerSdp.isNullOrBlank() && (status == "broadcasting" || status == "reconnecting" || status == "calling")) {
                    val isReconnect = reconnectTs > 0L && reconnectTs != lastAppliedReconnectTs
                    val offerKey = "${sessionId}_${reconnectTs}_${offerSdp.hashCode()}"
                    if (offerKey != lastAppliedOfferSessionKey) {
                        lastAppliedOfferSessionKey = offerKey
                        if (reconnectTs > 0L) {
                            lastAppliedReconnectTs = reconnectTs
                        }
                        if (status == "reconnecting") {
                            scope.launch {
                                _events.emit(SignalingEvent.ReconnectRequested(System.currentTimeMillis()))
                            }
                        }
                        scope.launch {
                            _events.emit(
                                SignalingEvent.OfferReceived(
                                    sdp = offerSdp,
                                    sessionId = sessionId,
                                    isReconnect = isReconnect
                                )
                            )
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "listenAsViewer onCancelled: ${error.message}")
                currentRoomId?.let { startRestPolling(it) }
            }
        }
        roomValueListener = listener
        ref.addValueEventListener(listener)
    }

    private fun listenForRemoteCandidates(
        ref: DatabaseReference,
        childPath: String,
        isCallerCandidate: Boolean
    ) {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val key = snapshot.key ?: return
                if (processedCandidateKeys.add("${childPath}_$key")) {
                    val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    val sdpMid = snapshot.child("sdpMid").getValue(String::class.java)
                    val sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                    val serverUrl = snapshot.child("serverUrl").getValue(String::class.java)
                    val candidate = IceCandidateModel(sdp, sdpMid, sdpMLineIndex, serverUrl)
                    scope.launch {
                        _events.emit(SignalingEvent.IceCandidateReceived(candidate, isCallerCandidate))
                    }
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        candidatesListener = listener
        ref.child(childPath).addChildEventListener(listener)
    }

    // REST Fallback for resilient zero-config signaling
    private fun getRestBaseUrl(): String {
        val custom = customDatabaseUrl?.trim()?.takeIf { it.isNotBlank() }
        if (custom != null) {
            return if (custom.endsWith("/")) custom.dropLast(1) else custom
        }
        return DEFAULT_FALLBACK_RTDB
    }

    private fun fallbackRestStartBroadcast(channelId: String, roomMap: Map<String, Any>) {
        scope.launch {
            val json = JSONObject(roomMap).toString()
            postRestData("rooms/$channelId", json)
            startRestPolling(channelId)
        }
    }

    private fun startRestPolling(channelId: String) {
        restPollingJob?.cancel()
        restPollingJob = scope.launch {
            val baseUrl = getRestBaseUrl()
            while (isActive) {
                delay(1000)
                try {
                    val url = "$baseUrl/rooms/$channelId.json"
                    val request = Request.Builder().url(url).get().build()
                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrBlank() && body != "null") {
                        val json = JSONObject(body)
                        val status = json.optString("status")

                        if (isBroadcaster) {
                            // Check remote command
                            val cmdObj = json.optJSONObject("remoteCommand")
                            if (cmdObj != null) {
                                handleIncomingCommand(
                                    id = cmdObj.optString("id"),
                                    actionName = cmdObj.optString("action"),
                                    value = cmdObj.optBoolean("value", false),
                                    timestamp = cmdObj.optLong("timestamp", 0L)
                                )
                            }

                            if (json.has("callee")) {
                                val calleeObj = json.optJSONObject("callee")
                                val sdp = calleeObj?.optString("sdp")
                                if (!sdp.isNullOrBlank() && sdp != lastAppliedAnswerSdp) {
                                    lastAppliedAnswerSdp = sdp
                                    _events.emit(SignalingEvent.AnswerReceived(sdp))
                                }
                            }
                            if (status == "viewer_left" && !hasNotifiedViewerLeftForSession) {
                                hasNotifiedViewerLeftForSession = true
                                _events.emit(SignalingEvent.ViewerLeft(System.currentTimeMillis()))
                            }
                        } else {
                            // Viewer reads callerMedia state
                            val mediaObj = json.optJSONObject("callerMedia")
                            if (mediaObj != null) {
                                _events.emit(
                                    SignalingEvent.PeerMediaStateChanged(
                                        isCameraEnabled = mediaObj.optBoolean("cameraEnabled", true),
                                        isMicEnabled = mediaObj.optBoolean("micEnabled", true),
                                        isFrontCamera = mediaObj.optBoolean("isFrontCamera", false),
                                        broadcastActive = mediaObj.optBoolean("broadcastActive", status != "standby")
                                    )
                                )
                            }

                            if (status == "standby") {
                                lastAppliedOfferSessionKey = null
                                processedCandidateKeys.clear()
                                if (!lastStandbyNotified) {
                                    lastStandbyNotified = true
                                    _events.emit(SignalingEvent.BroadcastStandby)
                                }
                            } else {
                                lastStandbyNotified = false
                                if (status == "ended" && lastAppliedOfferSessionKey != null) {
                                    lastAppliedOfferSessionKey = null
                                    processedCandidateKeys.clear()
                                    _events.emit(SignalingEvent.PeerDisconnected("Broadcast stopped by camera"))
                                } else if (json.has("caller") && (status == "broadcasting" || status == "reconnecting")) {
                                    val callerObj = json.optJSONObject("caller")
                                    val offerSdp = callerObj?.optString("sdp")
                                    val sessionId = callerObj?.optString("sessionId") ?: ""
                                    val reconnectTs = callerObj?.optLong("reconnectTimestamp", 0L) ?: 0L
                                    if (!offerSdp.isNullOrBlank()) {
                                        val offerKey = "${sessionId}_${reconnectTs}_${offerSdp.hashCode()}"
                                        if (offerKey != lastAppliedOfferSessionKey) {
                                            lastAppliedOfferSessionKey = offerKey
                                            _events.emit(
                                                SignalingEvent.OfferReceived(
                                                    sdp = offerSdp,
                                                    sessionId = sessionId,
                                                    isReconnect = reconnectTs > 0L
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Check candidates
                        val targetCandidateKey = if (isBroadcaster) "calleeCandidates" else "callerCandidates"
                        if (json.has(targetCandidateKey)) {
                            val candObj = json.optJSONObject(targetCandidateKey)
                            candObj?.keys()?.forEach { key ->
                                val candidateKey = "${targetCandidateKey}_$key"
                                if (processedCandidateKeys.add(candidateKey)) {
                                    val cJson = candObj.optJSONObject(key)
                                    if (cJson != null) {
                                        val sdp = cJson.optString("sdp")
                                        val sdpMid = cJson.optString("sdpMid")
                                        val sdpMLineIndex = cJson.optInt("sdpMLineIndex", 0)
                                        val serverUrl = cJson.optString("serverUrl")
                                        val candidate = IceCandidateModel(sdp, sdpMid, sdpMLineIndex, serverUrl)
                                        _events.emit(SignalingEvent.IceCandidateReceived(candidate, !isBroadcaster))
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore transient polling glitches
                }
            }
        }
    }

    private fun postRestData(path: String, json: String, isPush: Boolean = false) {
        try {
            val baseUrl = getRestBaseUrl()
            val url = "$baseUrl/$path.json"
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = json.toRequestBody(mediaType)
            val request = if (isPush) {
                Request.Builder().url(url).post(requestBody).build()
            } else {
                Request.Builder().url(url).put(requestBody).build()
            }
            httpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "postRestData failed for $path: ${e.message}")
        }
    }

    fun stopBroadcast() {
        val roomId = currentRoomId
        if (roomId != null) {
            updateStatus("ended")
        }
        cleanup()
    }

    fun leaveAsViewer() {
        val roomId = currentRoomId
        if (roomId != null) {
            updateStatus("viewer_left")
        }
        cleanup()
    }

    private fun cleanupListenersOnly() {
        restPollingJob?.cancel()
        restPollingJob = null

        val ref = roomRef
        if (ref != null) {
            roomValueListener?.let { ref.removeEventListener(it) }
            candidatesListener?.let {
                ref.child("calleeCandidates").removeEventListener(it)
                ref.child("callerCandidates").removeEventListener(it)
            }
        }
        roomValueListener = null
        candidatesListener = null
    }

    fun cleanup() {
        cleanupListenersOnly()
        roomRef = null
        currentRoomId = null
        processedCandidateKeys.clear()
        lastAppliedAnswerSdp = null
        lastAppliedOfferSessionKey = null
        lastAppliedReconnectTs = 0L
        lastProcessedCommandId = null
        hasNotifiedViewerLeftForSession = false
        lastStandbyNotified = false
    }
}
