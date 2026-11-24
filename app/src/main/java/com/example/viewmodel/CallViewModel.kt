package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AppAudioManager
import com.example.service.CallForegroundService
import com.example.signaling.IceCandidateModel
import com.example.signaling.SignalingClient
import com.example.signaling.SignalingEvent
import com.example.webrtc.SurveillanceRole
import com.example.webrtc.WebRtcClient
import com.example.webrtc.WebRtcEvent
import com.example.webrtc.WebRtcLiveStats
import com.example.webrtc.reconnect.DefaultReconnectPolicy
import com.example.webrtc.reconnect.DisconnectReason
import com.example.webrtc.reconnect.ReconnectContext
import com.example.webrtc.reconnect.ReconnectMode
import com.example.webrtc.reconnect.ReconnectPolicy
import com.example.webrtc.record.RemoteCallRecorder
import com.example.webrtc.record.RecordingStatus
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
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

enum class SurveillanceMode {
    NONE,
    BROADCAST,
    VIEW
}

sealed class CallState {
    object Idle : CallState()
    data class WaitingForPeer(val channelId: String) : CallState()
    data class JoiningCall(val channelId: String) : CallState()
    data class Connected(val channelId: String, val durationSeconds: Long = 0) : CallState()
    data class Reconnecting(
        val channelId: String,
        val attempt: Int,
        val reason: String,
        val maxAttempts: Int = 30
    ) : CallState()
    data class Ended(val reason: String) : CallState()
    data class Error(val message: String) : CallState()
}

data class CallUiStats(
    val iceConnectionState: String = "NEW",
    val connectionState: String = "NEW",
    val isCaller: Boolean = false,
    val localCandidatesCount: Int = 0,
    val remoteCandidatesCount: Int = 0,
    val reconnectCount: Int = 0,
    val reconnectMode: String = "RESUME"
)

class CallViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "CallViewModel"
        private const val PREFS_NAME = "camguard_prefs"
        private const val KEY_CUSTOM_RTDB = "custom_rtdb_url"
        private const val KEY_ACTIVE_CHANNEL = "active_channel_id"
        private const val KEY_RECENT_CHANNELS = "recent_channels"
        const val DEFAULT_CHANNEL_ID = "cam-01"

        @Volatile
        var activeInstance: CallViewModel? = null
            private set
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var webRtcClient: WebRtcClient? = null
        private set

    var signalingClient: SignalingClient = SignalingClient(prefs.getString(KEY_CUSTOM_RTDB, null))
        private set

    val audioManager: AppAudioManager = AppAudioManager(application)

    private val _surveillanceMode = MutableStateFlow(SurveillanceMode.NONE)
    val surveillanceMode: StateFlow<SurveillanceMode> = _surveillanceMode.asStateFlow()

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _activeChannelId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE_CHANNEL, DEFAULT_CHANNEL_ID)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CHANNEL_ID
    )
    val activeChannelId: StateFlow<String> = _activeChannelId.asStateFlow()

    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    private val _isCaller = MutableStateFlow(false)
    val isCaller: StateFlow<Boolean> = _isCaller.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _isRemoteCameraEnabled = MutableStateFlow(true)
    val isRemoteCameraEnabled: StateFlow<Boolean> = _isRemoteCameraEnabled.asStateFlow()

    private val _isRemoteMicEnabled = MutableStateFlow(true)
    val isRemoteMicEnabled: StateFlow<Boolean> = _isRemoteMicEnabled.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(true)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(true)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _isRemoteAudioMuted = MutableStateFlow(false)
    val isRemoteAudioMuted: StateFlow<Boolean> = _isRemoteAudioMuted.asStateFlow()

    private val _isStealthMode = MutableStateFlow(false)
    val isStealthMode: StateFlow<Boolean> = _isStealthMode.asStateFlow()

    private val _recentChannels = MutableStateFlow<List<String>>(loadRecentChannels())
    val recentChannels: StateFlow<List<String>> = _recentChannels.asStateFlow()

    private val _customDatabaseUrl = MutableStateFlow(prefs.getString(KEY_CUSTOM_RTDB, ""))
    val customDatabaseUrl: StateFlow<String?> = _customDatabaseUrl.asStateFlow()

    private val _stats = MutableStateFlow(CallUiStats())
    val stats: StateFlow<CallUiStats> = _stats.asStateFlow()

    private val _liveStats = MutableStateFlow(WebRtcLiveStats())
    val liveStats: StateFlow<WebRtcLiveStats> = _liveStats.asStateFlow()

    private val _showLiveHud = MutableStateFlow(false)
    val showLiveHud: StateFlow<Boolean> = _showLiveHud.asStateFlow()

    private val _recordingStatus = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus.asStateFlow()

    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    private var remoteRecorder: RemoteCallRecorder? = null
    private var durationTimerJob: Job? = null
    private var statsPollingJob: Job? = null
    private var reconnectJob: Job? = null
    private var disconnectDebounceJob: Job? = null
    private var webRtcObserverJobs = mutableListOf<Job>()

    private var localCandidates = 0
    private var remoteCandidates = 0
    private var reconnectAttempts = 0
    private var hasReconnectedOnce = false
    private var currentSessionId: String = ""
    private var lastViewerSessionId: String = ""
    private val reconnectPolicy: ReconnectPolicy = DefaultReconnectPolicy()

    init {
        activeInstance = this
        observeSignalingEvents()
    }

    fun setInPipMode(inPip: Boolean) {
        _isInPipMode.value = inPip
    }

    fun setActiveChannelId(channelId: String) {
        val sanitized = sanitizeChannelId(channelId)
        _activeChannelId.value = sanitized
        if (sanitized.isNotBlank()) {
            prefs.edit().putString(KEY_ACTIVE_CHANNEL, sanitized).apply()
        }
    }

    fun generateRandomChannelId(): String {
        val code = "cam-${Random.nextInt(1000, 9999)}"
        setActiveChannelId(code)
        return code
    }

    fun setCustomDatabaseUrl(url: String) {
        val cleanUrl = url.trim()
        prefs.edit().putString(KEY_CUSTOM_RTDB, cleanUrl).apply()
        _customDatabaseUrl.value = cleanUrl
        signalingClient.updateCustomUrl(if (cleanUrl.isBlank()) null else cleanUrl)
    }

    private fun sanitizeChannelId(raw: String): String {
        return raw.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]"), "-")
            .take(24)
    }

    private fun initWebRtc(role: SurveillanceRole, enableAudio: Boolean = true) {
        cancelWebRtcObservers()
        webRtcClient?.close()

        val client = WebRtcClient(getApplication())
        webRtcClient = client

        if (role == SurveillanceRole.BROADCASTER) {
            client.startLocalMedia(preferBackCamera = true, enableAudio = enableAudio)
            _isMicEnabled.value = enableAudio && client.isMicEnabled.value
            _isCameraEnabled.value = true
        } else {
            _isRemoteAudioMuted.value = false
        }

        webRtcObserverJobs += viewModelScope.launch {
            client.events.collect { event -> handleWebRtcEvent(event) }
        }
        webRtcObserverJobs += viewModelScope.launch {
            client.remoteVideoTrack.collect { track -> _remoteVideoTrack.value = track }
        }
        webRtcObserverJobs += viewModelScope.launch {
            client.isFrontCamera.collect { isFront -> _isFrontCamera.value = isFront }
        }
        webRtcObserverJobs += viewModelScope.launch {
            client.isMicEnabled.collect { mic -> _isMicEnabled.value = mic }
        }
        webRtcObserverJobs += viewModelScope.launch {
            client.isCameraEnabled.collect { cam -> _isCameraEnabled.value = cam }
        }
        webRtcObserverJobs += viewModelScope.launch {
            client.isRemoteAudioMuted.collect { muted -> _isRemoteAudioMuted.value = muted }
        }

        if (role == SurveillanceRole.VIEWER) {
            val rec = RemoteCallRecorder(getApplication(), client.rootEglBase.eglBaseContext)
            remoteRecorder = rec
            webRtcObserverJobs += viewModelScope.launch {
                rec.recordingStatus.collect { status -> _recordingStatus.value = status }
            }
        } else {
            remoteRecorder = null
            _recordingStatus.value = RecordingStatus.Idle
        }
    }

    private fun cancelWebRtcObservers() {
        webRtcObserverJobs.forEach { it.cancel() }
        webRtcObserverJobs.clear()
    }

    private fun observeSignalingEvents() {
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                handleSignalingEvent(event)
            }
        }
    }

    /**
     * Single-tap BROADCAST mode (Camera Unit).
     * Captures rear camera + optional mic and streams one-way (sendonly) on the channel.
     */
    fun startBroadcast(channelId: String = _activeChannelId.value, enableAudio: Boolean = true) {
        val cleanId = sanitizeChannelId(channelId).ifBlank { DEFAULT_CHANNEL_ID }
        setActiveChannelId(cleanId)
        saveRecentChannel(cleanId)

        _surveillanceMode.value = SurveillanceMode.BROADCAST
        _currentRoomId.value = cleanId
        _isCaller.value = true
        _isStealthMode.value = false
        _callDurationSeconds.value = 0L
        localCandidates = 0
        remoteCandidates = 0
        reconnectAttempts = 0
        hasReconnectedOnce = false
        currentSessionId = UUID.randomUUID().toString().take(8)

        initWebRtc(role = SurveillanceRole.BROADCASTER, enableAudio = enableAudio)
        CallForegroundService.startService(getApplication(), isBroadcaster = true, channelId = cleanId)

        val rtc = webRtcClient ?: return
        rtc.createPeerConnection(SurveillanceRole.BROADCASTER)
        _callState.value = CallState.WaitingForPeer(cleanId)
        startDurationTimer()

        rtc.createOffer(
            isBroadcaster = true,
            onSdpCreated = { sessionDescription ->
                Log.d(TAG, "Broadcast Offer created for channel '$cleanId' (session=$currentSessionId)")
                signalingClient.startBroadcast(cleanId, sessionDescription.description, currentSessionId)
                signalingClient.updateMediaState(
                    isCameraEnabled = _isCameraEnabled.value,
                    isMicEnabled = _isMicEnabled.value
                )
            }
        )
    }

    /**
     * Single-tap VIEW mode (Monitor Unit).
     * Receive-only (recvonly) — requires zero camera or microphone permissions!
     * Automatically waits if the Broadcaster isn't live yet and connects the moment it starts.
     */
    fun startViewing(channelId: String = _activeChannelId.value) {
        val cleanId = sanitizeChannelId(channelId).ifBlank { DEFAULT_CHANNEL_ID }
        setActiveChannelId(cleanId)
        saveRecentChannel(cleanId)

        _surveillanceMode.value = SurveillanceMode.VIEW
        _currentRoomId.value = cleanId
        _isCaller.value = false
        _isStealthMode.value = false
        _callDurationSeconds.value = 0L
        _isRemoteCameraEnabled.value = true
        _isRemoteMicEnabled.value = true
        _remoteVideoTrack.value = null
        localCandidates = 0
        remoteCandidates = 0
        reconnectAttempts = 0
        hasReconnectedOnce = false
        lastViewerSessionId = ""

        initWebRtc(role = SurveillanceRole.VIEWER, enableAudio = false)
        audioManager.start()
        CallForegroundService.startService(getApplication(), isBroadcaster = false, channelId = cleanId)

        val rtc = webRtcClient ?: return
        rtc.createPeerConnection(SurveillanceRole.VIEWER)
        _callState.value = CallState.JoiningCall(cleanId)

        signalingClient.joinAsViewer(cleanId)
    }

    /**
     * When a viewer disconnects cleanly, keep the Broadcaster camera running uninterrupted
     * and publish a fresh SDP Offer so the next viewer can connect immediately with 1 tap.
     */
    private fun resetBroadcasterForNextViewer() {
        val channelId = _currentRoomId.value ?: return
        val rtc = webRtcClient ?: return
        if (_surveillanceMode.value != SurveillanceMode.BROADCAST) return

        Log.d(TAG, "Viewer left channel '$channelId'. Refreshing broadcast offer for next viewer...")
        reconnectJob?.cancel()
        disconnectDebounceJob?.cancel()
        stopStatsPolling()

        localCandidates = 0
        remoteCandidates = 0
        reconnectAttempts = 0
        hasReconnectedOnce = false
        currentSessionId = UUID.randomUUID().toString().take(8)
        _callState.value = CallState.WaitingForPeer(channelId)

        rtc.rebuildPeerConnection(SurveillanceRole.BROADCASTER) { sdpOffer ->
            signalingClient.startBroadcast(channelId, sdpOffer.description, currentSessionId)
            signalingClient.updateMediaState(
                isCameraEnabled = _isCameraEnabled.value,
                isMicEnabled = _isMicEnabled.value
            )
        }
    }

    private fun handleSignalingEvent(event: SignalingEvent) {
        when (event) {
            is SignalingEvent.OfferReceived -> {
                if (_surveillanceMode.value == SurveillanceMode.VIEW) {
                    val rtc = webRtcClient ?: return
                    val isNewBroadcastSession = lastViewerSessionId.isNotBlank() &&
                        event.sessionId.isNotBlank() &&
                        event.sessionId != lastViewerSessionId

                    if (isNewBroadcastSession) {
                        Log.d(TAG, "New broadcaster session detected (${event.sessionId}); rebuilding viewer PeerConnection")
                        localCandidates = 0
                        remoteCandidates = 0
                        rtc.rebuildPeerConnection(SurveillanceRole.VIEWER)
                    }
                    lastViewerSessionId = event.sessionId

                    rtc.setRemoteDescription(event.sdp, SessionDescription.Type.OFFER) {
                        rtc.createAnswer { answerDesc ->
                            signalingClient.sendAnswer(answerDesc.description)
                        }
                    }
                }
            }
            is SignalingEvent.AnswerReceived -> {
                if (_surveillanceMode.value == SurveillanceMode.BROADCAST) {
                    val rtc = webRtcClient ?: return
                    rtc.setRemoteDescription(event.sdp, SessionDescription.Type.ANSWER) {
                        Log.d(TAG, "Viewer Answer applied successfully on Broadcaster")
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
            is SignalingEvent.ViewerLeft -> {
                if (_surveillanceMode.value == SurveillanceMode.BROADCAST) {
                    resetBroadcasterForNextViewer()
                }
            }
            is SignalingEvent.PeerDisconnected -> {
                if (_surveillanceMode.value == SurveillanceMode.VIEW) {
                    // Broadcaster stopped: finalize any active recording safely, then wait for broadcaster to resume
                    if (remoteRecorder?.isRecording == true) {
                        remoteRecorder?.stopRecording()
                    }
                    _remoteVideoTrack.value = null
                    stopDurationTimer()
                    stopStatsPolling()
                    reconnectJob?.cancel()
                    val channelId = _currentRoomId.value
                    if (channelId != null) {
                        _callState.value = CallState.JoiningCall(channelId)
                        webRtcClient?.rebuildPeerConnection(SurveillanceRole.VIEWER)
                    } else {
                        _callState.value = CallState.Ended(event.reason)
                    }
                }
            }
            is SignalingEvent.PeerMediaStateChanged -> {
                _isRemoteCameraEnabled.value = event.isCameraEnabled
                _isRemoteMicEnabled.value = event.isMicEnabled
            }
            is SignalingEvent.ReconnectRequested -> {
                val channelId = _currentRoomId.value ?: return
                if (_surveillanceMode.value == SurveillanceMode.VIEW) {
                    reconnectAttempts++
                    val mode = if (reconnectAttempts <= 3) ReconnectMode.RESUME else ReconnectMode.FULL_RECONNECT
                    updateStats()
                    // Resume stays silent to the UI; Full Reconnect fires once via hasReconnectedOnce gate
                    if (mode == ReconnectMode.FULL_RECONNECT) {
                        _remoteVideoTrack.value = null
                        if (!hasReconnectedOnce) {
                            hasReconnectedOnce = true
                            _callState.value = CallState.Reconnecting(
                                channelId = channelId,
                                attempt = reconnectAttempts,
                                reason = "Camera initiated full reconnect",
                                maxAttempts = 30
                            )
                        }
                    }
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
                        disconnectDebounceJob?.cancel()
                        val channelId = _currentRoomId.value ?: ""
                        reconnectJob?.cancel()
                        reconnectAttempts = 0
                        hasReconnectedOnce = false
                        if (_callState.value !is CallState.Connected) {
                            _callState.value = CallState.Connected(channelId, _callDurationSeconds.value)
                        }
                        startDurationTimer()
                        startStatsPolling()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        disconnectDebounceJob?.cancel()
                        disconnectDebounceJob = viewModelScope.launch {
                            delay(4000)
                            if (isActive && _callState.value is CallState.Connected) {
                                triggerReconnection("ICE Disconnected")
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        disconnectDebounceJob?.cancel()
                        triggerReconnection("ICE Failed - Network Drop")
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        disconnectDebounceJob?.cancel()
                        if (_surveillanceMode.value == SurveillanceMode.VIEW) {
                            stopDurationTimer()
                        }
                    }
                    else -> {}
                }
            }
            is WebRtcEvent.ConnectionStateChanged -> {
                updateStats()
                when (event.state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        disconnectDebounceJob?.cancel()
                        val channelId = _currentRoomId.value ?: ""
                        reconnectJob?.cancel()
                        reconnectAttempts = 0
                        hasReconnectedOnce = false
                        if (_callState.value !is CallState.Connected) {
                            _callState.value = CallState.Connected(channelId, _callDurationSeconds.value)
                        }
                        startDurationTimer()
                        startStatsPolling()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        disconnectDebounceJob?.cancel()
                        disconnectDebounceJob = viewModelScope.launch {
                            delay(4000)
                            if (isActive && _callState.value is CallState.Connected) {
                                triggerReconnection("Peer Connection Dropped")
                            }
                        }
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        disconnectDebounceJob?.cancel()
                        triggerReconnection("Peer Connection Failed")
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
        val channelId = _currentRoomId.value ?: return
        val role = if (_surveillanceMode.value == SurveillanceMode.BROADCAST) {
            SurveillanceRole.BROADCASTER
        } else {
            SurveillanceRole.VIEWER
        }

        reconnectAttempts++
        val mode = if (reconnectAttempts <= 3) ReconnectMode.RESUME else ReconnectMode.FULL_RECONNECT
        updateStats()

        // Fast resume is silent to the UI; Full Reconnect escalates once via hasReconnectedOnce gate.
        if (mode == ReconnectMode.FULL_RECONNECT) {
            _remoteVideoTrack.value = null
            if (!hasReconnectedOnce) {
                hasReconnectedOnce = true
                _callState.value = CallState.Reconnecting(
                    channelId = channelId,
                    attempt = reconnectAttempts,
                    reason = reason,
                    maxAttempts = 30
                )
            }
        }

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val context = ReconnectContext(
                attemptIndex = reconnectAttempts,
                retryCount = reconnectAttempts - 1,
                mode = mode,
                reason = when {
                    reason.contains("ICE", ignoreCase = true) -> DisconnectReason.ICE_FAILED
                    reason.contains("Peer", ignoreCase = true) -> DisconnectReason.PEER_CONNECTION_DISCONNECTED
                    reason.contains("Manual", ignoreCase = true) -> DisconnectReason.MANUAL_TRIGGER
                    else -> DisconnectReason.UNKNOWN
                },
                cumulativeElapsedTimeMs = 0L
            )
            val delayDuration = reconnectPolicy.getNextRetryDelay(context)
            if (delayDuration == null) {
                if (role == SurveillanceRole.BROADCASTER) {
                    // Broadcaster never dies on viewer drop; reset cleanly for next viewer
                    resetBroadcasterForNextViewer()
                } else {
                    _callState.value = CallState.Error("Connection lost after 30 attempts. Tap View to reconnect.")
                }
                return@launch
            }

            delay(delayDuration.inWholeMilliseconds)
            if (!isActive) return@launch
            if (mode == ReconnectMode.FULL_RECONNECT && _callState.value !is CallState.Reconnecting) return@launch

            when (mode) {
                ReconnectMode.RESUME -> {
                    Log.d(TAG, "Triggering Fast Resume (silent ICE restart)")
                    if (role == SurveillanceRole.BROADCASTER) {
                        webRtcClient?.restartIce(isBroadcaster = true) { sdpOffer ->
                            signalingClient.sendReconnectOffer(sdpOffer.description, currentSessionId)
                        }
                    } else {
                        signalingClient.updateStatus("reconnecting")
                    }
                }
                ReconnectMode.FULL_RECONNECT -> {
                    Log.d(TAG, "Triggering Full Reconnect (rebuilding PeerConnection)")
                    localCandidates = 0
                    remoteCandidates = 0
                    webRtcClient?.rebuildPeerConnection(role = role) { sdpOffer ->
                        signalingClient.sendReconnectOffer(sdpOffer.description, currentSessionId)
                    }
                    if (role == SurveillanceRole.VIEWER) {
                        signalingClient.updateStatus("reconnecting")
                    }
                }
            }

            delay(8000)
            if (isActive && (_callState.value is CallState.Reconnecting || (_callState.value is CallState.Connected && mode == ReconnectMode.RESUME))) {
                if (reconnectAttempts < 30) {
                    val nextMode = if (reconnectAttempts + 1 <= 3) "Resume" else "Full Reconnect"
                    triggerReconnection("$nextMode retry #${reconnectAttempts + 1}")
                } else if (role == SurveillanceRole.BROADCASTER) {
                    resetBroadcasterForNextViewer()
                } else {
                    _callState.value = CallState.Error("Connection lost after 30 attempts. Tap View to reconnect.")
                }
            }
        }
    }

    fun forceReconnect() {
        val channelId = _currentRoomId.value ?: return
        if (_surveillanceMode.value == SurveillanceMode.VIEW && _callState.value is CallState.JoiningCall) {
            // Re-subscribe viewer cleanly if still waiting
            signalingClient.joinAsViewer(channelId)
        } else {
            triggerReconnection("Manual Reconnect")
        }
    }

    private fun startDurationTimer() {
        if (durationTimerJob != null) return
        durationTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _callDurationSeconds.value += 1
                val totalSec = _callDurationSeconds.value
                val min = totalSec / 60
                val sec = totalSec % 60
                val durationText = String.format(Locale.US, "%02d:%02d", min, sec)
                val channelId = _currentRoomId.value ?: ""
                val isBroadcaster = _surveillanceMode.value == SurveillanceMode.BROADCAST
                CallForegroundService.updateService(getApplication(), isBroadcaster, channelId, durationText)
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

    fun fetchStatsNow() {
        val rtc = webRtcClient ?: return
        val currentStrategy = when {
            reconnectAttempts == 0 -> "NONE"
            reconnectAttempts <= 3 -> "RESUME"
            else -> "FULL_RECONNECT"
        }
        rtc.fetchLiveStats { newStats ->
            _liveStats.value = newStats.copy(
                isCaller = _isCaller.value,
                localCandidatesCount = localCandidates,
                remoteCandidatesCount = remoteCandidates,
                reconnectCount = reconnectAttempts,
                reconnectStrategy = currentStrategy
            )
            _stats.value = CallUiStats(
                iceConnectionState = newStats.iceConnectionState,
                connectionState = newStats.connectionState,
                isCaller = _isCaller.value,
                localCandidatesCount = localCandidates,
                remoteCandidatesCount = remoteCandidates,
                reconnectCount = reconnectAttempts,
                reconnectMode = currentStrategy
            )
        }
    }

    private fun updateStats() {
        val rtc = webRtcClient
        val currentStrategy = when {
            reconnectAttempts == 0 -> "NONE"
            reconnectAttempts <= 3 -> "RESUME"
            else -> "FULL_RECONNECT"
        }
        _stats.value = CallUiStats(
            iceConnectionState = rtc?.iceConnectionState?.value?.name ?: "UNKNOWN",
            connectionState = rtc?.connectionState?.value?.name ?: "UNKNOWN",
            isCaller = _isCaller.value,
            localCandidatesCount = localCandidates,
            remoteCandidatesCount = remoteCandidates,
            reconnectCount = reconnectAttempts,
            reconnectMode = currentStrategy
        )
    }

    // Broadcaster Controls
    fun toggleMicrophone() {
        webRtcClient?.let {
            val enabled = it.toggleMicrophone()
            _isMicEnabled.value = enabled
            signalingClient.updateMediaState(
                isCameraEnabled = _isCameraEnabled.value,
                isMicEnabled = enabled
            )
        }
    }

    fun toggleCamera() {
        webRtcClient?.let {
            val enabled = it.toggleCamera()
            _isCameraEnabled.value = enabled
            signalingClient.updateMediaState(
                isCameraEnabled = enabled,
                isMicEnabled = _isMicEnabled.value
            )
        }
    }

    fun switchCamera() {
        webRtcClient?.switchCamera { isFront ->
            _isFrontCamera.value = isFront
        }
    }

    fun toggleStealthMode(enabled: Boolean? = null) {
        _isStealthMode.value = enabled ?: !_isStealthMode.value
    }

    // Viewer Controls
    fun toggleRemoteAudio() {
        webRtcClient?.let {
            _isRemoteAudioMuted.value = it.toggleRemoteAudio()
        }
    }

    fun toggleSpeaker() {
        audioManager.toggleSpeaker()
    }

    fun toggleRemoteRecording(): Boolean {
        val rec = remoteRecorder ?: return false
        return if (rec.isRecording) {
            rec.stopRecording()
            true
        } else {
            val track = _remoteVideoTrack.value
            val channel = _currentRoomId.value
            rec.startRecording(track, channel, webRtcClient)
        }
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

    fun stopSession() {
        CallForegroundService.stopService(getApplication())

        // Always finalize any active recording before tearing down WebRTC
        if (remoteRecorder?.isRecording == true) {
            remoteRecorder?.stopRecording()
        }

        when (_surveillanceMode.value) {
            SurveillanceMode.BROADCAST -> signalingClient.stopBroadcast()
            SurveillanceMode.VIEW -> signalingClient.leaveAsViewer()
            SurveillanceMode.NONE -> signalingClient.cleanup()
        }

        stopDurationTimer()
        stopStatsPolling()
        reconnectJob?.cancel()
        disconnectDebounceJob?.cancel()
        cancelWebRtcObservers()

        webRtcClient?.close()
        webRtcClient = null

        if (_surveillanceMode.value == SurveillanceMode.VIEW) {
            audioManager.stop()
        }

        hasReconnectedOnce = false
        _isStealthMode.value = false
        _surveillanceMode.value = SurveillanceMode.NONE
        _callState.value = CallState.Idle
        _currentRoomId.value = null
        _callDurationSeconds.value = 0L
        _remoteVideoTrack.value = null
        _liveStats.value = WebRtcLiveStats()
    }

    fun endCall() {
        stopSession()
    }

    fun resetToIdle() {
        _callState.value = CallState.Idle
        _surveillanceMode.value = SurveillanceMode.NONE
        _currentRoomId.value = null
        _callDurationSeconds.value = 0L
        _remoteVideoTrack.value = null
        _isStealthMode.value = false
    }

    private fun loadRecentChannels(): List<String> {
        val raw = prefs.getString(KEY_RECENT_CHANNELS, "") ?: ""
        return if (raw.isBlank()) listOf(DEFAULT_CHANNEL_ID) else raw.split(",").filter { it.isNotBlank() }
    }

    private fun saveRecentChannel(channelId: String) {
        val current = loadRecentChannels().toMutableList()
        current.remove(channelId)
        current.add(0, channelId)
        val trimmed = current.take(5)
        prefs.edit().putString(KEY_RECENT_CHANNELS, trimmed.joinToString(",")).apply()
        _recentChannels.value = trimmed
    }

    override fun onCleared() {
        super.onCleared()
        if (activeInstance == this) {
            activeInstance = null
        }
        stopSession()
    }
}
