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
        private const val STALE_DEVICE_MS = 45_000L
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

    // Always-on device presence + remote wake command listener
    private var myDeviceId: String = ""
    private var myDeviceName: String = ""
    private var myDeviceStatus: String = "ready"
    private var remoteWakeRef: DatabaseReference? = null
    private var remoteWakeListener: ValueEventListener? = null
    private var devicesListRef: DatabaseReference? = null
    private var devicesListListener: ValueEventListener? = null
    private var heartbeatJob: Job? = null
    private var devicesRestPollingJob: Job? = null
    private var remoteWakeRestPollingJob: Job? = null

    private var restPollingJob: Job? = null
    private val processedCandidateKeys = mutableSetOf<String>()

    private var lastAppliedAnswerSdp: String? = null
    private var lastAppliedOfferSessionKey: String? = null
    private var lastAppliedReconnectTs: Long = 0L
    private var lastProcessedCommandId: String? = null
    private var appStartedAtMs: Long = System.currentTimeMillis()
    private var hasNotifiedViewerLeftForSession: Boolean = false
    private var lastStandbyNotified: Boolean = false

    init {
        initFirebaseDatabase()
    }

    fun updateCustomUrl(url: String?) {
        customDatabaseUrl = url
        initFirebaseDatabase()
        if (myDeviceId.isNotBlank()) {
            registerDevicePresence(myDeviceId, myDeviceName, myDeviceStatus)
            startObservingOnlineDevices()
        }
    }

    private fun initFirebaseDatabase() {
        try {
            val url = customDatabaseUrl?.trim()?.takeIf { it.isNotBlank() }
            firebaseDb = if (url != null) {
                FirebaseDatabase.getInstance(url)
            } else {
                try {
                    FirebaseDatabase.getInstance()
                } catch (_: Exception) {
                    FirebaseDatabase.getInstance(DEFAULT_FALLBACK_RTDB)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseDatabase init note: ${e.message}. Using REST fallback if needed.")
        }
    }

    // =========================================================================
    // Always-On Device Presence & Remote Wake Trigger (Zero Manual Setup)
    // =========================================================================

    fun registerDevicePresence(deviceId: String, deviceName: String, status: String = "ready") {
        myDeviceId = deviceId
        myDeviceName = deviceName
        myDeviceStatus = status
        appStartedAtMs = System.currentTimeMillis()

        pushPresenceNow()
        attachRemoteWakeListener(deviceId)

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(10_000)
                pushPresenceNow()
            }
        }
    }

    fun updateDevicePresence(deviceName: String = myDeviceName, status: String = myDeviceStatus) {
        if (myDeviceId.isBlank()) return
        myDeviceName = deviceName
        myDeviceStatus = status
        pushPresenceNow()
    }

    fun markDeviceOffline() {
        if (myDeviceId.isBlank()) return
        val offlineMap = mapOf(
            "deviceId" to myDeviceId,
            "deviceName" to myDeviceName,
            "online" to false,
            "status" to "offline",
            "lastSeen" to System.currentTimeMillis()
        )
        firebaseDb?.getReference("devices")?.child(myDeviceId)?.setValue(offlineMap)
        scope.launch {
            postRestData("devices/$myDeviceId", JSONObject(offlineMap).toString())
        }
        heartbeatJob?.cancel()
        remoteWakeRestPollingJob?.cancel()
        devicesRestPollingJob?.cancel()
    }

    private fun pushPresenceNow() {
        if (myDeviceId.isBlank()) return
        val now = System.currentTimeMillis()
        val data = mapOf(
            "deviceId" to myDeviceId,
            "deviceName" to myDeviceName,
            "online" to true,
            "status" to myDeviceStatus,
            "lastSeen" to now
        )
        val db = firebaseDb
        if (db != null) {
            try {
                val ref = db.getReference("devices").child(myDeviceId)
                ref.onDisconnect().updateChildren(
                    mapOf("online" to false, "status" to "offline")
                )
                ref.setValue(data).addOnFailureListener {
                    scope.launch { postRestData("devices/$myDeviceId", JSONObject(data).toString()) }
                }
                return
            } catch (_: Exception) {
                // Fall back to REST
            }
        }
        scope.launch {
            postRestData("devices/$myDeviceId", JSONObject(data).toString())
        }
    }

    private fun attachRemoteWakeListener(deviceId: String) {
        remoteWakeListener?.let { remoteWakeRef?.removeEventListener(it) }
        remoteWakeRestPollingJob?.cancel()

        val db = firebaseDb
        if (db != null) {
            try {
                val cmdRef = db.getReference("rooms").child(deviceId).child("remoteCommand")
                remoteWakeRef = cmdRef
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!snapshot.exists()) return
                        val cmdId = snapshot.child("id").getValue(String::class.java)
                        val actionName = snapshot.child("action").getValue(String::class.java)
                        val value = snapshot.child("value").getValue(Boolean::class.java) ?: false
                        val ts = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                        handleIncomingCommand(cmdId, actionName, value, ts)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        startRemoteWakeRestPolling(deviceId)
                    }
                }
                remoteWakeListener = listener
                cmdRef.addValueEventListener(listener)
                return
            } catch (_: Exception) {
                // Fallback to REST
            }
        }
        startRemoteWakeRestPolling(deviceId)
    }

    private fun startRemoteWakeRestPolling(deviceId: String) {
        remoteWakeRestPollingJob?.cancel()
        remoteWakeRestPollingJob = scope.launch {
            val baseUrl = getRestBaseUrl()
            while (isActive) {
                delay(1500)
                try {
                    val req = Request.Builder().url("$baseUrl/rooms/$deviceId/remoteCommand.json").get().build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank() && body != "null") {
                        val json = JSONObject(body)
                        handleIncomingCommand(
                            id = json.optString("id"),
                            actionName = json.optString("action"),
                            value = json.optBoolean("value", false),
                            timestamp = json.optLong("timestamp", 0L)
                        )
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun startObservingOnlineDevices() {
        devicesListListener?.let { devicesListRef?.removeEventListener(it) }
        devicesRestPollingJob?.cancel()

        val db = firebaseDb
        if (db != null) {
            try {
                val ref = db.getReference("devices")
                devicesListRef = ref
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val now = System.currentTimeMillis()
                        val list = mutableListOf<OnlineDevice>()
                        for (child in snapshot.children) {
                            val id = child.child("deviceId").getValue(String::class.java) ?: child.key ?: continue
                            val name = child.child("deviceName").getValue(String::class.java) ?: id
                            val online = child.child("online").getValue(Boolean::class.java) ?: false
                            val status = child.child("status").getValue(String::class.java) ?: "ready"
                            val lastSeen = child.child("lastSeen").getValue(Long::class.java) ?: 0L
                            if (online && (now - lastSeen) <= STALE_DEVICE_MS) {
                                list.add(OnlineDevice(id, name, online, status, lastSeen))
                            }
                        }
                        list.sortByDescending { it.lastSeen }
                        scope.launch {
                            _events.emit(SignalingEvent.OnlineDevicesUpdated(list))
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        startDevicesRestPolling()
                    }
                }
                devicesListListener = listener
                ref.addValueEventListener(listener)
                return
            } catch (_: Exception) {
            }
        }
        startDevicesRestPolling()
    }

    private fun startDevicesRestPolling() {
        devicesRestPollingJob?.cancel()
        devicesRestPollingJob = scope.launch {
            val baseUrl = getRestBaseUrl()
            while (isActive) {
                try {
                    val req = Request.Builder().url("$baseUrl/devices.json").get().build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank() && body != "null") {
                        val root = JSONObject(body)
                        val now = System.currentTimeMillis()
                        val list = mutableListOf<OnlineDevice>()
                        root.keys().forEach { key ->
                            val obj = root.optJSONObject(key)
                            if (obj != null) {
                                val id = obj.optString("deviceId", key)
                                val name = obj.optString("deviceName", id)
                                val online = obj.optBoolean("online", false)
                                val status = obj.optString("status", "ready")
                                val lastSeen = obj.optLong("lastSeen", 0L)
                                if (online && (now - lastSeen) <= STALE_DEVICE_MS) {
                                    list.add(OnlineDevice(id, name, online, status, lastSeen))
                                }
                            }
                        }
                        list.sortByDescending { it.lastSeen }
                        _events.emit(SignalingEvent.OnlineDevicesUpdated(list))
                    } else if (resp.isSuccessful) {
                        _events.emit(SignalingEvent.OnlineDevicesUpdated(emptyList()))
                    }
                } catch (_: Exception) {
                }
                delay(2500)
            }
        }
    }

    // =========================================================================
    // Broadcast & Viewer WebRTC Signaling
    // =========================================================================

    fun startBroadcast(
        deviceId: String,
        sdpOffer: String,
        sessionId: String,
        isFrontCamera: Boolean = false,
        isMicEnabled: Boolean = true
    ) {
        cleanupListenersOnly()
        this.currentRoomId = deviceId
        this.isBroadcaster = true
        processedCandidateKeys.clear()
        lastAppliedAnswerSdp = null
        lastAppliedOfferSessionKey = null
        hasNotifiedViewerLeftForSession = false

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
            "createdAt" to System.currentTimeMillis()
        )

        val db = firebaseDb
        if (db != null) {
            try {
                val ref = db.getReference("rooms").child(deviceId)
                roomRef = ref
                ref.setValue(roomMap).addOnSuccessListener {
                    Log.d(TAG, "Broadcast live for device '$deviceId' (session=$sessionId)")
                    listenAsBroadcaster(ref)
                    listenForRemoteCandidates(ref, childPath = "calleeCandidates", isCallerCandidate = false)
                }.addOnFailureListener {
                    fallbackRestStartBroadcast(deviceId, roomMap)
                }
            } catch (_: Exception) {
                fallbackRestStartBroadcast(deviceId, roomMap)
            }
        } else {
            fallbackRestStartBroadcast(deviceId, roomMap)
        }
    }

    fun enterStandbyMode(isFrontCamera: Boolean) {
        val deviceId = currentRoomId ?: return
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
            ref.setValue(standbyMap).addOnFailureListener {
                scope.launch { postRestData("rooms/$deviceId", JSONObject(standbyMap).toString()) }
            }
        } else {
            scope.launch { postRestData("rooms/$deviceId", JSONObject(standbyMap).toString()) }
        }
    }

    fun joinAsViewer(targetDeviceId: String) {
        cleanupListenersOnly()
        this.currentRoomId = targetDeviceId
        this.isBroadcaster = false
        processedCandidateKeys.clear()
        lastAppliedOfferSessionKey = null
        lastAppliedReconnectTs = 0L
        lastStandbyNotified = false

        val db = firebaseDb
        if (db != null) {
            try {
                val ref = db.getReference("rooms").child(targetDeviceId)
                roomRef = ref
                listenAsViewer(ref)
                listenForRemoteCandidates(ref, childPath = "callerCandidates", isCallerCandidate = true)
            } catch (_: Exception) {
                startRestPolling(targetDeviceId)
            }
        } else {
            startRestPolling(targetDeviceId)
        }
    }

    fun sendRemoteCommand(
        action: RemoteCommandAction,
        value: Boolean = false,
        targetDeviceId: String? = null
    ) {
        val channelId = targetDeviceId ?: currentRoomId ?: return
        val cmdId = "cmd_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val cmdMap = mapOf(
            "id" to cmdId,
            "action" to action.name,
            "value" to value,
            "timestamp" to System.currentTimeMillis()
        )
        Log.d(TAG, "Sending remote command to '$channelId': $action (value=$value)")
        val db = firebaseDb
        if (db != null) {
            db.getReference("rooms").child(channelId).child("remoteCommand").setValue(cmdMap)
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
        roomRef?.child(targetPath)?.push()?.setValue(candidateMap)
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
        if (timestamp > 0L && timestamp < appStartedAtMs - 10_000L) return

        val action = try {
            RemoteCommandAction.valueOf(actionName)
        } catch (_: Exception) {
            return
        }

        lastProcessedCommandId = id
        val cmd = RemoteCommand(id = id, action = action, value = value, timestamp = timestamp)
        Log.d(TAG, "Device received remote command: $cmd")
        scope.launch {
            _events.emit(SignalingEvent.RemoteCommandReceived(cmd))
        }
    }

    private fun listenAsBroadcaster(ref: DatabaseReference) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val calleeSnap = snapshot.child("callee")
                val answerSdp = calleeSnap.child("sdp").getValue(String::class.java)
                if (!answerSdp.isNullOrBlank() && answerSdp != lastAppliedAnswerSdp) {
                    lastAppliedAnswerSdp = answerSdp
                    scope.launch { _events.emit(SignalingEvent.AnswerReceived(answerSdp)) }
                }

                when (snapshot.child("status").getValue(String::class.java)) {
                    "viewer_left" -> {
                        if (!hasNotifiedViewerLeftForSession) {
                            hasNotifiedViewerLeftForSession = true
                            scope.launch { _events.emit(SignalingEvent.ViewerLeft(System.currentTimeMillis())) }
                        }
                    }
                    "reconnecting" -> {
                        scope.launch { _events.emit(SignalingEvent.ReconnectRequested(System.currentTimeMillis())) }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
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

                val mediaSnap = snapshot.child("callerMedia")
                if (mediaSnap.exists()) {
                    val cameraEnabled = mediaSnap.child("cameraEnabled").getValue(Boolean::class.java) ?: true
                    val micEnabled = mediaSnap.child("micEnabled").getValue(Boolean::class.java) ?: true
                    val isFront = mediaSnap.child("isFrontCamera").getValue(Boolean::class.java) ?: false
                    val broadcastActive = mediaSnap.child("broadcastActive").getValue(Boolean::class.java) ?: (status != "standby")
                    scope.launch {
                        _events.emit(
                            SignalingEvent.PeerMediaStateChanged(cameraEnabled, micEnabled, isFront, broadcastActive)
                        )
                    }
                }

                if (status == "standby") {
                    lastAppliedOfferSessionKey = null
                    processedCandidateKeys.clear()
                    if (!lastStandbyNotified) {
                        lastStandbyNotified = true
                        scope.launch { _events.emit(SignalingEvent.BroadcastStandby) }
                    }
                    return
                } else {
                    lastStandbyNotified = false
                }

                if (status == "ended" && lastAppliedOfferSessionKey != null) {
                    lastAppliedOfferSessionKey = null
                    processedCandidateKeys.clear()
                    scope.launch { _events.emit(SignalingEvent.PeerDisconnected("Broadcast stopped")) }
                    return
                }

                if (!offerSdp.isNullOrBlank() && (status == "broadcasting" || status == "reconnecting" || status == "calling")) {
                    val isReconnect = reconnectTs > 0L && reconnectTs != lastAppliedReconnectTs
                    val offerKey = "${sessionId}_${reconnectTs}_${offerSdp.hashCode()}"
                    if (offerKey != lastAppliedOfferSessionKey) {
                        lastAppliedOfferSessionKey = offerKey
                        if (reconnectTs > 0L) lastAppliedReconnectTs = reconnectTs
                        if (status == "reconnecting") {
                            scope.launch { _events.emit(SignalingEvent.ReconnectRequested(System.currentTimeMillis())) }
                        }
                        scope.launch {
                            _events.emit(SignalingEvent.OfferReceived(offerSdp, sessionId, isReconnect))
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
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

    private fun getRestBaseUrl(): String {
        val custom = customDatabaseUrl?.trim()?.takeIf { it.isNotBlank() }
        if (custom != null) {
            return if (custom.endsWith("/")) custom.dropLast(1) else custom
        }
        return DEFAULT_FALLBACK_RTDB
    }

    private fun fallbackRestStartBroadcast(channelId: String, roomMap: Map<String, Any>) {
        scope.launch {
            postRestData("rooms/$channelId", JSONObject(roomMap).toString())
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
                    val req = Request.Builder().url("$baseUrl/rooms/$channelId.json").get().build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank() && body != "null") {
                        val json = JSONObject(body)
                        val status = json.optString("status")

                        if (isBroadcaster) {
                            val calleeObj = json.optJSONObject("callee")
                            val sdp = calleeObj?.optString("sdp")
                            if (!sdp.isNullOrBlank() && sdp != lastAppliedAnswerSdp) {
                                lastAppliedAnswerSdp = sdp
                                _events.emit(SignalingEvent.AnswerReceived(sdp))
                            }
                            if (status == "viewer_left" && !hasNotifiedViewerLeftForSession) {
                                hasNotifiedViewerLeftForSession = true
                                _events.emit(SignalingEvent.ViewerLeft(System.currentTimeMillis()))
                            }
                        } else {
                            val mediaObj = json.optJSONObject("callerMedia")
                            if (mediaObj != null) {
                                _events.emit(
                                    SignalingEvent.PeerMediaStateChanged(
                                        mediaObj.optBoolean("cameraEnabled", true),
                                        mediaObj.optBoolean("micEnabled", true),
                                        mediaObj.optBoolean("isFrontCamera", false),
                                        mediaObj.optBoolean("broadcastActive", status != "standby")
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
                                    _events.emit(SignalingEvent.PeerDisconnected("Broadcast stopped"))
                                } else if (json.has("caller") && (status == "broadcasting" || status == "reconnecting")) {
                                    val callerObj = json.optJSONObject("caller")
                                    val offerSdp = callerObj?.optString("sdp")
                                    val sessionId = callerObj?.optString("sessionId") ?: ""
                                    val reconnectTs = callerObj?.optLong("reconnectTimestamp", 0L) ?: 0L
                                    if (!offerSdp.isNullOrBlank()) {
                                        val offerKey = "${sessionId}_${reconnectTs}_${offerSdp.hashCode()}"
                                        if (offerKey != lastAppliedOfferSessionKey) {
                                            lastAppliedOfferSessionKey = offerKey
                                            _events.emit(SignalingEvent.OfferReceived(offerSdp, sessionId, reconnectTs > 0L))
                                        }
                                    }
                                }
                            }
                        }

                        val targetCandidateKey = if (isBroadcaster) "calleeCandidates" else "callerCandidates"
                        val candObj = json.optJSONObject(targetCandidateKey)
                        candObj?.keys()?.forEach { key ->
                            if (processedCandidateKeys.add("${targetCandidateKey}_$key")) {
                                val cJson = candObj.optJSONObject(key)
                                if (cJson != null) {
                                    val candidate = IceCandidateModel(
                                        sdp = cJson.optString("sdp"),
                                        sdpMid = cJson.optString("sdpMid"),
                                        sdpMLineIndex = cJson.optInt("sdpMLineIndex", 0),
                                        serverUrl = cJson.optString("serverUrl")
                                    )
                                    _events.emit(SignalingEvent.IceCandidateReceived(candidate, !isBroadcaster))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
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
        hasNotifiedViewerLeftForSession = false
        lastStandbyNotified = false
    }
}
