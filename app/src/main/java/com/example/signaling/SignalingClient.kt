package com.example.signaling

import android.util.Log
import com.google.firebase.FirebaseApp
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
    private var isCaller: Boolean = false
    private var firebaseDb: FirebaseDatabase? = null
    private var roomRef: DatabaseReference? = null

    private var offerAnswerListener: ValueEventListener? = null
    private var candidatesListener: ChildEventListener? = null
    private var statusListener: ValueEventListener? = null

    private var restPollingJob: Job? = null
    private val processedCandidateKeys = mutableSetOf<String>()

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

    fun createRoom(roomId: String, sdpOffer: String) {
        this.currentRoomId = roomId
        this.isCaller = true
        processedCandidateKeys.clear()

        val offerMap = mapOf(
            "sdp" to sdpOffer,
            "type" to "OFFER"
        )
        val roomMap = mapOf(
            "caller" to offerMap,
            "status" to "calling",
            "createdAt" to System.currentTimeMillis()
        )

        val db = firebaseDb
        if (db != null) {
            try {
                val ref = db.getReference("rooms").child(roomId)
                roomRef = ref
                ref.setValue(roomMap).addOnSuccessListener {
                    Log.d(TAG, "Room $roomId created on Firebase RTDB")
                    listenForCalleeAnswer(ref)
                    listenForCalleeCandidates(ref)
                    listenForRoomStatus(ref)
                }.addOnFailureListener { err ->
                    Log.w(TAG, "Firebase SDK setValue failed: ${err.message}. Starting REST sync...")
                    fallbackRestCreateRoom(roomId, offerMap)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firebase error, using REST: ${e.message}")
                fallbackRestCreateRoom(roomId, offerMap)
            }
        } else {
            fallbackRestCreateRoom(roomId, offerMap)
        }
    }

    fun joinRoom(roomId: String, onOfferReceived: (String) -> Unit) {
        this.currentRoomId = roomId
        this.isCaller = false
        processedCandidateKeys.clear()

        val db = firebaseDb
        if (db != null) {
            try {
                val ref = db.getReference("rooms").child(roomId)
                roomRef = ref
                ref.child("caller").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val sdp = snapshot.child("sdp").getValue(String::class.java)
                        if (sdp != null) {
                            onOfferReceived(sdp)
                            listenForCallerCandidates(ref)
                            listenForRoomStatus(ref)
                        } else {
                            // Try REST fallback fetch
                            fallbackRestFetchOffer(roomId, onOfferReceived)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        fallbackRestFetchOffer(roomId, onOfferReceived)
                    }
                })
            } catch (e: Exception) {
                fallbackRestFetchOffer(roomId, onOfferReceived)
            }
        } else {
            fallbackRestFetchOffer(roomId, onOfferReceived)
        }
    }

    fun sendAnswer(sdpAnswer: String) {
        val roomId = currentRoomId ?: return
        val answerMap = mapOf(
            "sdp" to sdpAnswer,
            "type" to "ANSWER"
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

        val targetPath = if (isCaller) "callerCandidates" else "calleeCandidates"
        val ref = roomRef
        if (ref != null) {
            ref.child(targetPath).push().setValue(candidateMap)
        }
        scope.launch {
            postRestData("rooms/$roomId/$targetPath", JSONObject(candidateMap).toString(), isPush = true)
        }
    }

    fun sendReconnectOffer(sdpOffer: String) {
        val roomId = currentRoomId ?: return
        val offerMap = mapOf(
            "sdp" to sdpOffer,
            "type" to "OFFER",
            "reconnectTimestamp" to System.currentTimeMillis()
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

    private fun listenForCalleeAnswer(ref: DatabaseReference) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val calleeSnap = snapshot.child("callee")
                val sdp = calleeSnap.child("sdp").getValue(String::class.java)
                if (!sdp.isNullOrBlank()) {
                    scope.launch {
                        _events.emit(SignalingEvent.AnswerReceived(sdp))
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "listenForCalleeAnswer onCancelled: ${error.message}")
            }
        }
        offerAnswerListener = listener
        ref.addValueEventListener(listener)
    }

    private fun listenForCalleeCandidates(ref: DatabaseReference) {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val key = snapshot.key ?: return
                if (processedCandidateKeys.add("callee_$key")) {
                    val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    val sdpMid = snapshot.child("sdpMid").getValue(String::class.java)
                    val sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                    val serverUrl = snapshot.child("serverUrl").getValue(String::class.java)
                    val candidate = IceCandidateModel(sdp, sdpMid, sdpMLineIndex, serverUrl)
                    scope.launch {
                        _events.emit(SignalingEvent.IceCandidateReceived(candidate, isCallerCandidate = false))
                    }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        candidatesListener = listener
        ref.child("calleeCandidates").addChildEventListener(listener)
    }

    private fun listenForCallerCandidates(ref: DatabaseReference) {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val key = snapshot.key ?: return
                if (processedCandidateKeys.add("caller_$key")) {
                    val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    val sdpMid = snapshot.child("sdpMid").getValue(String::class.java)
                    val sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                    val serverUrl = snapshot.child("serverUrl").getValue(String::class.java)
                    val candidate = IceCandidateModel(sdp, sdpMid, sdpMLineIndex, serverUrl)
                    scope.launch {
                        _events.emit(SignalingEvent.IceCandidateReceived(candidate, isCallerCandidate = true))
                    }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        candidatesListener = listener
        ref.child("callerCandidates").addChildEventListener(listener)
    }

    private fun listenForRoomStatus(ref: DatabaseReference) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                if (status == "ended") {
                    scope.launch {
                        _events.emit(SignalingEvent.PeerDisconnected("Call ended by peer"))
                    }
                } else if (status == "reconnecting") {
                    scope.launch {
                        _events.emit(SignalingEvent.ReconnectRequested(System.currentTimeMillis()))
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        statusListener = listener
        ref.addValueEventListener(listener)
    }

    // REST Fallback for resilient zero-config signaling
    private fun getRestBaseUrl(): String {
        val custom = customDatabaseUrl?.trim()?.takeIf { it.isNotBlank() }
        if (custom != null) {
            return if (custom.endsWith("/")) custom.dropLast(1) else custom
        }
        return DEFAULT_FALLBACK_RTDB
    }

    private fun fallbackRestCreateRoom(roomId: String, offerMap: Map<String, Any>) {
        scope.launch {
            val json = JSONObject(mapOf("caller" to offerMap, "status" to "calling", "createdAt" to System.currentTimeMillis())).toString()
            postRestData("rooms/$roomId", json)
            startRestPolling(roomId)
        }
    }

    private fun fallbackRestFetchOffer(roomId: String, onOfferReceived: (String) -> Unit) {
        scope.launch {
            val baseUrl = getRestBaseUrl()
            val url = "$baseUrl/rooms/$roomId/caller.json"
            try {
                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank() && body != "null") {
                    val json = JSONObject(body)
                    val sdp = json.optString("sdp")
                    if (sdp.isNotBlank()) {
                        onOfferReceived(sdp)
                        startRestPolling(roomId)
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fallbackRestFetchOffer error: ${e.message}")
            }
            _events.emit(SignalingEvent.Error("Could not find active call for Room ID $roomId"))
        }
    }

    private fun startRestPolling(roomId: String) {
        restPollingJob?.cancel()
        restPollingJob = scope.launch {
            val baseUrl = getRestBaseUrl()
            while (isActive) {
                delay(1200)
                try {
                    val url = "$baseUrl/rooms/$roomId.json"
                    val request = Request.Builder().url(url).get().build()
                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrBlank() && body != "null") {
                        val json = JSONObject(body)

                        // Check answer if caller
                        if (isCaller && json.has("callee")) {
                            val calleeObj = json.optJSONObject("callee")
                            val sdp = calleeObj?.optString("sdp")
                            if (!sdp.isNullOrBlank()) {
                                _events.emit(SignalingEvent.AnswerReceived(sdp))
                            }
                        }

                        // Check candidates
                        val targetCandidateKey = if (isCaller) "calleeCandidates" else "callerCandidates"
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
                                        _events.emit(SignalingEvent.IceCandidateReceived(candidate, !isCaller))
                                    }
                                }
                            }
                        }

                        // Status
                        val status = json.optString("status")
                        if (status == "ended") {
                            _events.emit(SignalingEvent.PeerDisconnected("Call ended"))
                        }
                    }
                } catch (e: Exception) {
                    // Ignore network polling glitches
                }
            }
        }
    }

    private fun postRestData(path: String, json: String, isPush: Boolean = false) {
        try {
            val baseUrl = getRestBaseUrl()
            val url = if (isPush) "$baseUrl/$path.json" else "$baseUrl/$path.json"
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

    fun endCall() {
        val roomId = currentRoomId
        if (roomId != null) {
            updateStatus("ended")
        }
        cleanup()
    }

    fun cleanup() {
        restPollingJob?.cancel()
        restPollingJob = null

        val ref = roomRef
        if (ref != null) {
            offerAnswerListener?.let { ref.removeEventListener(it) }
            statusListener?.let { ref.removeEventListener(it) }
            candidatesListener?.let {
                ref.child("calleeCandidates").removeEventListener(it)
                ref.child("callerCandidates").removeEventListener(it)
            }
        }

        offerAnswerListener = null
        statusListener = null
        candidatesListener = null
        roomRef = null
        currentRoomId = null
        processedCandidateKeys.clear()
    }
}
