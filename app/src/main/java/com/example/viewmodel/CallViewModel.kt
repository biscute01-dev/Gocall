package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AppAudioManager
import com.example.signaling.IceCandidateModel
import com.example.signaling.SignalingClient
import com.example.signaling.SignalingEvent
import com.example.webrtc.WebRtcClient
import com.example.webrtc.WebRtcEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import com.example.webrtc.WebRtcLiveStats
import com.example.webrtc.record.RemoteCallRecorder
import com.example.webrtc.record.RecordingStatus
import java.io.File
import android.net.Uri
import android.content.Intent
import kotlin.random.Random

sealed class CallState {
    object Idle : CallState()
    data class CreatingCall(val roomId: String) : CallState()
    data class WaitingForPeer(val roomId: String) : CallState()
    data class JoiningCall(val roomId: String) : CallState()
    data class Connected(val roomId: String, val durationSeconds: Long = 0) : CallState()
    data class Reconnecting(val roomId: String, val attempt: Int, val reason: String) : CallState()
    data class Ended(val reason: String) : CallState()
    data class Error(val message: String) : CallState()
}

data class CallUiStats(
    val iceConnectionState: String = "NEW",
    val connectionState: String = "NEW",
    val isCaller: Boolean = false,
    val localCandidatesCount: Int = 0,
    val remoteCandidatesCount: Int = 0,
    val reconnectCount: Int = 0
)

class CallViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "CallViewModel"
        private const val PREFS_NAME = "video_call_prefs"
        private const val KEY_CUSTOM_RTDB = "custom_rtdb_url"
        private const val KEY_RECENT_ROOMS = "recent_rooms"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var webRtcClient: WebRtcClient? = null
        private set

    var signalingClient: SignalingClient = SignalingClient(prefs.getString(KEY_CUSTOM_RTDB, null))
        private set

    val audioManager: AppAudioManager = AppAudioManager(application)

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    private val _isCaller = MutableStateFlow(false)
    val isCaller: StateFlow<Boolean> = _isCaller.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(true)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(true)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _isSpeakerOn = audioManager.isSpeakerOn

    private val _recentRooms = MutableStateFlow<List<String>>(loadRecentRooms())
    val recentRooms: StateFlow<List<String>> = _recentRooms.asStateFlow()

    private val _customDatabaseUrl = MutableStateFlow(prefs.getString(KEY_CUSTOM_RTDB, ""))
    val customDatabaseUrl: StateFlow<String?> = _customDatabaseUrl.asStateFlow()

    private val _stats = MutableStateFlow(CallUiStats())
    val stats: StateFlow<CallUiStats> = _stats.asStateFlow()

    private val _liveStats = MutableStateFlow(WebRtcLiveStats())
    val liveStats: StateFlow<WebRtcLiveStats> = _liveStats.asStateFlow()

    private val _showLiveHud = MutableStateFlow(true)
    val showLiveHud: StateFlow<Boolean> = _showLiveHud.asStateFlow()

    private val _recordingStatus = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus.asStateFlow()

    private var remoteRecorder: RemoteCallRecorder? = null
    private var durationTimerJob: Job? = null
    private var statsPollingJob: Job? = null
    private var reconnectJob: Job? = null
    private var localCandidates = 0
    private var remoteCandidates = 0
    private var reconnectAttempts = 0

    init {
        observeSignalingEvents()
    }

    private fun initWebRtc() {
        if (webRtcClient == null) {
            val client = WebRtcClient(getApplication())
            webRtcClient = client
            client.startLocalMedia()

            viewModelScope.launch {
                client.events.collect { event ->
                    handleWebRtcEvent(event)
                }
            }
            viewModelScope.launch {
                client.remoteVideoTrack.collect { track ->
                    _remoteVideoTrack.value = track
                }
            }
            viewModelScope.launch {
                client.isFrontCamera.collect { isFront ->
                    _isFrontCamera.value = isFront
                }
            }
            viewModelScope.launch {
                client.isMicEnabled.collect { mic ->
                    _isMicEnabled.value = mic
                }
            }
            viewModelScope.launch {
                client.isCameraEnabled.collect { cam ->
                    _isCameraEnabled.value = cam
                }
            }

            val rec = RemoteCallRecorder(getApplication(), client.rootEglBase.eglBaseContext)
            remoteRecorder = rec
            viewModelScope.launch {
                rec.recordingStatus.collect { status ->
                    _recordingStatus.value = status
                }
            }
        }
    }

    private fun observeSignalingEvents() {
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                handleSignalingEvent(event)
            }
        }
    }

    fun setCustomDatabaseUrl(url: String) {
        val cleanUrl = url.trim()
        prefs.edit().putString(KEY_CUSTOM_RTDB, cleanUrl).apply()
        _customDatabaseUrl.value = cleanUrl
        signalingClient.updateCustomUrl(if (cleanUrl.isBlank()) null else cleanUrl)
    }

    fun generateRandomRoomId(): String {
        val prefixes = listOf("call", "room", "meet", "talk", "live")
        val randomNum = Random.nextInt(1000, 9999)
        return "${prefixes.random()}-$randomNum"
    }

    fun createRoom(roomId: String = generateRandomRoomId()) {
        val cleanId = roomId.trim().lowercase()
        _currentRoomId.value = cleanId
        _isCaller.value = true
        _callState.value = CallState.CreatingCall(cleanId)
        localCandidates = 0
        remoteCandidates = 0
        reconnectAttempts = 0
        saveRecentRoom(cleanId)

        initWebRtc()
        audioManager.start()

        val rtc = webRtcClient ?: return
        rtc.createPeerConnection()
        _callState.value = CallState.WaitingForPeer(cleanId)

        rtc.createOffer(
            onSdpCreated = { sessionDescription ->
                Log.d(TAG, "Created Offer SDP for room $cleanId")
                signalingClient.createRoom(cleanId, sessionDescription.description)
            }
        )
    }

    fun joinRoom(roomId: String) {
        val cleanId = roomId.trim().lowercase()
        if (cleanId.isBlank()) {
            _callState.value = CallState.Error("Room ID cannot be empty")
            return
        }
        _currentRoomId.value = cleanId
        _isCaller.value = false
        _callState.value = CallState.JoiningCall(cleanId)
        localCandidates = 0
        remoteCandidates = 0
        reconnectAttempts = 0
        saveRecentRoom(cleanId)

        initWebRtc()
        audioManager.start()

        val rtc = webRtcClient ?: return
        rtc.createPeerConnection()

        signalingClient.joinRoom(cleanId) { offerSdp ->
            Log.d(TAG, "Received offer SDP, setting remote desc & creating answer")
            rtc.setRemoteDescription(offerSdp, SessionDescription.Type.OFFER) {
                rtc.createAnswer { answerDesc ->
                    Log.d(TAG, "Created Answer SDP, sending to Firebase RTDB")
                    signalingClient.sendAnswer(answerDesc.description)
                }
            }
        }
    }

    private fun handleSignalingEvent(event: SignalingEvent) {
        when (event) {
            is SignalingEvent.OfferReceived -> {
                if (!_isCaller.value) {
                    val rtc = webRtcClient ?: return
                    rtc.setRemoteDescription(event.sdp, SessionDescription.Type.OFFER) {
                        rtc.createAnswer { answerDesc ->
                            signalingClient.sendAnswer(answerDesc.description)
                        }
                    }
                }
            }
            is SignalingEvent.AnswerReceived -> {
                if (_isCaller.value) {
                    val rtc = webRtcClient ?: return
                    rtc.setRemoteDescription(event.sdp, SessionDescription.Type.ANSWER) {
                        Log.d(TAG, "Answer applied successfully on caller")
                    }
                }
            }
            is SignalingEvent.IceCandidateReceived -> {
                val candidate = IceCandidate(
                    event.candidate.sdpMid,
                    event.candidate.sdpMLineIndex,
                    event.candidate.sdp
                )
                remoteCandidates++
                updateStats()
                webRtcClient?.addIceCandidate(candidate)
            }
            is SignalingEvent.PeerDisconnected -> {
                _callState.value = CallState.Ended(event.reason)
                stopDurationTimer()
            }
            is SignalingEvent.ReconnectRequested -> {
                Log.d(TAG, "Peer requested reconnection...")
                if (!_isCaller.value) {
                    // Callee prepares for new answer when caller restarts ICE
                }
            }
            is SignalingEvent.Error -> {
                _callState.value = CallState.Error(event.message)
            }
        }
    }

    private fun handleWebRtcEvent(event: WebRtcEvent) {
        when (event) {
            is WebRtcEvent.IceCandidateGenerated -> {
                localCandidates++
                updateStats()
                val candidateModel = IceCandidateModel(
                    sdp = event.candidate.sdp,
                    sdpMid = event.candidate.sdpMid,
                    sdpMLineIndex = event.candidate.sdpMLineIndex,
                    serverUrl = event.candidate.serverUrl
                )
                signalingClient.sendIceCandidate(candidateModel)
            }
            is WebRtcEvent.IceStateChanged -> {
                updateStats()
                when (event.state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        val roomId = _currentRoomId.value ?: ""
                        reconnectJob?.cancel()
                        _callState.value = CallState.Connected(roomId, _callDurationSeconds.value)
                        startDurationTimer()
                        startStatsPolling()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        triggerReconnection("ICE Disconnected")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        triggerReconnection("ICE Failed - Network Drop")
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        stopDurationTimer()
                    }
                    else -> {}
                }
            }
            is WebRtcEvent.ConnectionStateChanged -> {
                updateStats()
                when (event.state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        val roomId = _currentRoomId.value ?: ""
                        reconnectJob?.cancel()
                        _callState.value = CallState.Connected(roomId, _callDurationSeconds.value)
                        startDurationTimer()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED -> {
                        triggerReconnection("Peer Connection Dropped")
                    }
                    else -> {}
                }
            }
            is WebRtcEvent.RemoteTrackReceived -> {
                _remoteVideoTrack.value = event.videoTrack
            }
            is WebRtcEvent.Error -> {
                Log.e(TAG, "WebRTC error: ${event.message}")
            }
        }
    }

    fun triggerReconnection(reason: String) {
        val roomId = _currentRoomId.value ?: return
        reconnectAttempts++
        updateStats()
        _callState.value = CallState.Reconnecting(roomId, reconnectAttempts, reason)

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            Log.d(TAG, "Triggering automatic reconnection attempt #$reconnectAttempts (Reason: $reason)")
            delay(1500) // Brief debounce for network switch

            if (_isCaller.value) {
                webRtcClient?.restartIce { sdpOffer ->
                    Log.d(TAG, "Generated ICE Restart Offer SDP")
                    signalingClient.sendReconnectOffer(sdpOffer.description)
                }
            } else {
                signalingClient.updateStatus("reconnecting")
            }

            // Fallback retry if still disconnected after 10s
            delay(8000)
            if (_callState.value is CallState.Reconnecting && isActive) {
                if (reconnectAttempts < 5) {
                    triggerReconnection("Retry #$reconnectAttempts")
                } else {
                    _callState.value = CallState.Error("Connection lost after multiple reconnection attempts. Please rejoin.")
                }
            }
        }
    }

    fun forceIceRestart() {
        triggerReconnection("Manual Reconnect")
    }

    private fun startDurationTimer() {
        if (durationTimerJob != null) return
        durationTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _callDurationSeconds.value += 1
            }
        }
    }

    private fun stopDurationTimer() {
        durationTimerJob?.cancel()
        durationTimerJob = null
    }

    fun startStatsPolling() {
        if (statsPollingJob != null) return
        statsPollingJob = viewModelScope.launch {
            while (isActive) {
                fetchStatsNow()
                delay(1000)
            }
        }
    }

    fun stopStatsPolling() {
        statsPollingJob?.cancel()
        statsPollingJob = null
    }

    fun toggleLiveHud() {
        _showLiveHud.value = !_showLiveHud.value
    }

    fun setLiveHud(visible: Boolean) {
        _showLiveHud.value = visible
    }

    fun fetchStatsNow() {
        val rtc = webRtcClient ?: return
        rtc.fetchLiveStats { newStats ->
            _liveStats.value = newStats.copy(
                isCaller = _isCaller.value,
                localCandidatesCount = localCandidates,
                remoteCandidatesCount = remoteCandidates,
                reconnectCount = reconnectAttempts
            )
            _stats.value = CallUiStats(
                iceConnectionState = newStats.iceConnectionState,
                connectionState = newStats.connectionState,
                isCaller = _isCaller.value,
                localCandidatesCount = localCandidates,
                remoteCandidatesCount = remoteCandidates,
                reconnectCount = reconnectAttempts
            )
        }
    }

    private fun updateStats() {
        val rtc = webRtcClient
        _stats.value = CallUiStats(
            iceConnectionState = rtc?.iceConnectionState?.value?.name ?: "UNKNOWN",
            connectionState = rtc?.connectionState?.value?.name ?: "UNKNOWN",
            isCaller = _isCaller.value,
            localCandidatesCount = localCandidates,
            remoteCandidatesCount = remoteCandidates,
            reconnectCount = reconnectAttempts
        )
    }

    fun toggleMicrophone() {
        webRtcClient?.let {
            val enabled = it.toggleMicrophone()
            _isMicEnabled.value = enabled
        }
    }

    fun toggleCamera() {
        webRtcClient?.let {
            val enabled = it.toggleCamera()
            _isCameraEnabled.value = enabled
        }
    }

    fun switchCamera() {
        webRtcClient?.switchCamera { isFront ->
            _isFrontCamera.value = isFront
        }
    }

    fun toggleSpeaker() {
        audioManager.toggleSpeaker()
    }

    /**
     * Toggles recording of the remote peer's video track and audio stream.
     */
    fun toggleRemoteRecording(): Boolean {
        val rec = remoteRecorder ?: return false
        return if (rec.isRecording) {
            rec.stopRecording()
            true
        } else {
            val track = _remoteVideoTrack.value
            val room = _currentRoomId.value
            rec.startRecording(track, room, webRtcClient)
        }
    }

    fun startRemoteRecording(): Boolean {
        val rec = remoteRecorder ?: return false
        val track = _remoteVideoTrack.value
        val room = _currentRoomId.value
        return rec.startRecording(track, room, webRtcClient)
    }

    fun stopRemoteRecording() {
        remoteRecorder?.stopRecording()
    }

    fun dismissRecordingStatus() {
        remoteRecorder?.resetStatus()
    }

    fun createShareRecordingIntent(file: File, mediaUri: Uri?): Intent? {
        return remoteRecorder?.createShareIntent(file, mediaUri)
    }

    fun createViewRecordingIntent(file: File, mediaUri: Uri?): Intent? {
        return remoteRecorder?.createViewIntent(file, mediaUri)
    }

    fun endCall() {
        // Automatically finalize and save any active recording before closing WebRTC
        if (remoteRecorder?.isRecording == true) {
            remoteRecorder?.stopRecording()
        }

        signalingClient.endCall()
        stopDurationTimer()
        stopStatsPolling()
        reconnectJob?.cancel()

        webRtcClient?.close()
        webRtcClient = null

        audioManager.stop()

        _callState.value = CallState.Ended("Call ended")
        _callDurationSeconds.value = 0
        _remoteVideoTrack.value = null
        _liveStats.value = WebRtcLiveStats()
    }

    fun resetToIdle() {
        _callState.value = CallState.Idle
        _currentRoomId.value = null
        _callDurationSeconds.value = 0
        _remoteVideoTrack.value = null
    }

    private fun loadRecentRooms(): List<String> {
        val raw = prefs.getString(KEY_RECENT_ROOMS, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(",").filter { it.isNotBlank() }
    }

    private fun saveRecentRoom(roomId: String) {
        val current = loadRecentRooms().toMutableList()
        current.remove(roomId)
        current.add(0, roomId)
        val trimmed = current.take(5)
        prefs.edit().putString(KEY_RECENT_ROOMS, trimmed.joinToString(",")).apply()
        _recentRooms.value = trimmed
    }

    fun clearRecentRooms() {
        prefs.edit().remove(KEY_RECENT_ROOMS).apply()
        _recentRooms.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        endCall()
    }
}
