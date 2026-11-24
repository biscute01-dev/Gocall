package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AppAudioManager
import com.example.service.CallForegroundService
import com.example.signaling.IceCandidateModel
import com.example.signaling.OnlineDevice
import com.example.signaling.RemoteCommand
import com.example.signaling.RemoteCommandAction
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

enum class SurveillanceMode {
    NONE,
    DEVICE_LIST,
    BROADCAST,
    VIEW
}

sealed class CallState {
    object Idle : CallState()
    data class WaitingForPeer(val deviceName: String) : CallState()
    data class JoiningCall(val deviceName: String) : CallState()
    data class Standby(val deviceName: String) : CallState()
    data class Connected(val deviceName: String, val durationSeconds: Long = 0) : CallState()
    data class Reconnecting(
        val deviceName: String,
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
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"

        @Volatile
        var activeInstance: CallViewModel? = null
            private set
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val myDeviceId: String = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
        ?: ("dev_" + UUID.randomUUID().toString().replace("-", "").take(10)).also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    private fun buildDefaultDeviceName(): String {
        val mfg = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val raw = when {
            model.isBlank() -> "Android Camera"
            mfg.isNotBlank() && model.startsWith(mfg, ignoreCase = true) -> model
            mfg.isNotBlank() -> "${mfg.replaceFirstChar { it.uppercase(Locale.US) }} $model"
            else -> model
        }
        return raw.take(26)
    }

    var webRtcClient: WebRtcClient? = null
        private set

    var signalingClient: SignalingClient = SignalingClient(prefs.getString(KEY_CUSTOM_RTDB, null))
        private set

    val audioManager: AppAudioManager = AppAudioManager(application)

    private val _deviceName = MutableStateFlow(
        prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: buildDefaultDeviceName()
    )
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _onlineDevices = MutableStateFlow<List<OnlineDevice>>(emptyList())
    val onlineDevices: StateFlow<List<OnlineDevice>> = _onlineDevices.asStateFlow()

    private val _targetDeviceName = MutableStateFlow("")
    val targetDeviceName: StateFlow<String> = _targetDeviceName.asStateFlow()

    private val _surveillanceMode = MutableStateFlow(SurveillanceMode.NONE)
    val surveillanceMode: StateFlow<SurveillanceMode> = _surveillanceMode.asStateFlow()

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    private val _isCaller = MutableStateFlow(false)
    val isCaller: StateFlow<Boolean> = _isCaller.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    // Remote broadcaster state mirrored in View mode
    private val _isRemoteCameraEnabled = MutableStateFlow(true)
    val isRemoteCameraEnabled: StateFlow<Boolean> = _isRemoteCameraEnabled.asStateFlow()

    private val _isRemoteMicEnabled = MutableStateFlow(true)
    val isRemoteMicEnabled: StateFlow<Boolean> = _isRemoteMicEnabled.asStateFlow()

    private val _isRemoteFrontCamera = MutableStateFlow(false)
    val isRemoteFrontCamera: StateFlow<Boolean> = _isRemoteFrontCamera.asStateFlow()

    private val _isRemoteBroadcastActive = MutableStateFlow(true)
    val isRemoteBroadcastActive: StateFlow<Boolean> = _isRemoteBroadcastActive.asStateFlow()

    // Local broadcaster state
    private val _isMicEnabled = MutableStateFlow(true)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(true)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    // Viewer local playback mute
    private val _isRemoteAudioMuted = MutableStateFlow(false)
    val isRemoteAudioMuted: StateFlow<Boolean> = _isRemoteAudioMuted.asStateFlow()

    private val _isStealthMode = MutableStateFlow(false)
    val isStealthMode: StateFlow<Boolean> = _isStealthMode.asStateFlow()

    private val _remoteCommandBanner = MutableStateFlow<String?>(null)
    val remoteCommandBanner: StateFlow<String?> = _remoteCommandBanner.asStateFlow()

    // Set when a remote viewer triggers START_BROADCAST on this device before camera permission is granted
    private val _pendingRemoteBroadcastWake = MutableStateFlow(false)
    val pendingRemoteBroadcastWake: StateFlow<Boolean> = _pendingRemoteBroadcastWake.asStateFlow()

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
    private var bannerClearJob: Job? = null
    private var viewerAutoWakeJob: Job? = null
    private var webRtcObserverJobs = mutableListOf<Job>()

    private var broadcastAudioAllowed: Boolean = true
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
        signalingClient.registerDevicePresence(myDeviceId, _deviceName.value, "ready")
        signalingClient.startObservingOnlineDevices()
    }

    fun setInPipMode(inPip: Boolean) {
        _isInPipMode.value = inPip
    }

    fun setDeviceName(name: String) {
        val clean = name.trim().take(26).ifBlank { buildDefaultDeviceName() }
        _deviceName.value = clean
        prefs.edit().putString(KEY_DEVICE_NAME, clean).apply()
        signalingClient.updateDevicePresence(deviceName = clean)
    }

    fun clearPendingRemoteBroadcastWake() {
        _pendingRemoteBroadcastWake.value = false
    }

    fun setCustomDatabaseUrl(url: String) {
        val cleanUrl = url.trim()
        prefs.edit().putString(KEY_CUSTOM_RTDB, cleanUrl).apply()
        _customDatabaseUrl.value = cleanUrl
        signalingClient.updateCustomUrl(if (cleanUrl.isBlank()) null else cleanUrl)
    }

    fun openDeviceList() {
        _surveillanceMode.value = SurveillanceMode.DEVICE_LIST
        signalingClient.updateDevicePresence(status = "ready")
        signalingClient.startObservingOnlineDevices()
    }

    fun closeDeviceList() {
        _surveillanceMode.value = SurveillanceMode.NONE
        signalingClient.updateDevicePresence(status = "ready")
    }

    fun refreshOnlineDevices() {
        signalingClient.startObservingOnlineDevices()
        showTransientBanner("Refreshing online devices...")
    }

    private fun showTransientBanner(message: String) {
        bannerClearJob?.cancel()
        _remoteCommandBanner.value = message
        bannerClearJob = viewModelScope.launch {
            delay(2500)
            _remoteCommandBanner.value = null
        }
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
            client.localVideoTrackFlow.collect { track -> _localVideoTrack.value = track }
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
     * Starts BROADCAST mode for this device (either locally or triggered remotely by a Viewer!).
     */
    fun startBroadcast(enableAudio: Boolean = true, fromRemote: Boolean = false) {
        val label = _deviceName.value
        _pendingRemoteBroadcastWake.value = false
        broadcastAudioAllowed = enableAudio

        _surveillanceMode.value = SurveillanceMode.BROADCAST
        _currentRoomId.value = myDeviceId
        _isCaller.value = true
        _isStealthMode.value = false
        _callDurationSeconds.value = 0L
        localCandidates = 0
        remoteCandidates = 0
        reconnectAttempts = 0
        hasReconnectedOnce = false
        currentSessionId = UUID.randomUUID().toString().take(8)

        initWebRtc(role = SurveillanceRole.BROADCASTER, enableAudio = enableAudio)
        CallForegroundService.startService(getApplication(), isBroadcaster = true, channelId = label)
        signalingClient.updateDevicePresence(status = "broadcasting")

        val rtc = webRtcClient ?: return
        rtc.createPeerConnection(SurveillanceRole.BROADCASTER)
        _callState.value = CallState.WaitingForPeer(label)
        startDurationTimer()

        rtc.createOffer(
            isBroadcaster = true,
            onSdpCreated = { sessionDescription ->
                Log.d(TAG, "Broadcast Offer created for device '$label' ($myDeviceId, session=$currentSessionId)")
                signalingClient.startBroadcast(
                    deviceId = myDeviceId,
                    sdpOffer = sessionDescription.description,
                    sessionId = currentSessionId,
                    isFrontCamera = _isFrontCamera.value,
                    isMicEnabled = _isMicEnabled.value
                )
            }
        )

        if (fromRemote) {
            showTransientBanner("Started broadcast remotely")
        }
    }

    /**
     * Tapping any online device in View Mode immediately starts its broadcast remotely
     * and connects straight into the live video feed!
     */
    fun connectToOnlineDevice(device: OnlineDevice) {
        _targetDeviceName.value = device.deviceName
        _surveillanceMode.value = SurveillanceMode.VIEW
        _currentRoomId.value = device.deviceId
        _isCaller.value = false
        _isStealthMode.value = false
        _callDurationSeconds.value = 0L
        _isRemoteCameraEnabled.value = true
        _isRemoteMicEnabled.value = true
        _isRemoteFrontCamera.value = false
        _isRemoteBroadcastActive.value = true
        _remoteVideoTrack.value = null
        localCandidates = 0
        remoteCandidates = 0
        reconnectAttempts = 0
        hasReconnectedOnce = false
        lastViewerSessionId = ""

        initWebRtc(role = SurveillanceRole.VIEWER, enableAudio = false)
        audioManager.start()
        CallForegroundService.startService(getApplication(), isBroadcaster = false, channelId = device.deviceName)
        signalingClient.updateDevicePresence(status = "viewing")

        val rtc = webRtcClient ?: return
        rtc.createPeerConnection(SurveillanceRole.VIEWER)
        _callState.value = CallState.JoiningCall(device.deviceName)

        // Start listening on target device's channel AND trigger remote broadcast start!
        signalingClient.joinAsViewer(device.deviceId)

        viewerAutoWakeJob?.cancel()
        if (device.status != "broadcasting") {
            signalingClient.sendRemoteCommand(
                action = RemoteCommandAction.START_BROADCAST,
                targetDeviceId = device.deviceId
            )
            showTransientBanner("Starting camera on ${device.deviceName}...")
        } else {
            // Device says broadcasting; if an offer isn't received quickly, nudge it with START_BROADCAST
            viewerAutoWakeJob = viewModelScope.launch {
                delay(2500)
                if (isActive && _surveillanceMode.value == SurveillanceMode.VIEW &&
                    _callState.value is CallState.JoiningCall && lastViewerSessionId.isBlank()
                ) {
                    signalingClient.sendRemoteCommand(
                        action = RemoteCommandAction.START_BROADCAST,
                        targetDeviceId = device.deviceId
                    )
                }
            }
        }
    }

    // =========================================================================
    // Remote Control Actions (Viewer -> Broadcaster)
    // =========================================================================

    fun remoteSwitchCamera() {
        if (_surveillanceMode.value != SurveillanceMode.VIEW) return
        signalingClient.sendRemoteCommand(RemoteCommandAction.SWITCH_CAMERA)
        showTransientBanner("Remote: Switching camera...")
    }

    fun remoteToggleBroadcasterMic() {
        if (_surveillanceMode.value != SurveillanceMode.VIEW) return
        val nextMicEnabled = !_isRemoteMicEnabled.value
        _isRemoteMicEnabled.value = nextMicEnabled
        signalingClient.sendRemoteCommand(RemoteCommandAction.SET_MIC, value = nextMicEnabled)
        showTransientBanner(if (nextMicEnabled) "Remote: Unmuting camera mic..." else "Remote: Muting camera mic...")
    }

    fun remoteStopBroadcast() {
        if (_surveillanceMode.value != SurveillanceMode.VIEW) return
        if (remoteRecorder?.isRecording == true) {
            remoteRecorder?.stopRecording()
        }
        signalingClient.sendRemoteCommand(RemoteCommandAction.STOP_BROADCAST)
        showTransientBanner("Remote: Ending broadcast...")
    }

    fun remoteStartBroadcast() {
        if (_surveillanceMode.value != SurveillanceMode.VIEW) return
        signalingClient.sendRemoteCommand(RemoteCommandAction.START_BROADCAST)
        showTransientBanner("Remote: Starting broadcast...")
    }

    // =========================================================================
    // Broadcaster Standby / Resume Lifecycle
    // =========================================================================

    fun enterBroadcasterStandby(fromRemote: Boolean = false) {
        val label = _deviceName.value
        if (_surveillanceMode.value != SurveillanceMode.BROADCAST) return

        Log.d(TAG, "Entering Broadcaster Standby (fromRemote=$fromRemote)")
        reconnectJob?.cancel()
        disconnectDebounceJob?.cancel()
        stopStatsPolling()
        stopDurationTimer()

        webRtcClient?.pauseBroadcastForStandby()
        _localVideoTrack.value = null
        _callState.value = CallState.Standby(label)

        signalingClient.enterStandbyMode(isFrontCamera = _isFrontCamera.value)
        signalingClient.updateDevicePresence(status = "standby")

        CallForegroundService.updateService(
            getApplication(),
            isBroadcaster = true,
            channelId = label,
            durationText = "Standby • Ready for remote start"
        )
        if (fromRemote) {
            showTransientBanner("Broadcast ended remotely")
        }
    }

    fun resumeBroadcasterFromStandby(fromRemote: Boolean = false) {
        val label = _deviceName.value
        val rtc = webRtcClient ?: return
        if (_surveillanceMode.value != SurveillanceMode.BROADCAST) return

        if (_callState.value !is CallState.Standby) {
            if (_callState.value is CallState.WaitingForPeer) {
                resetBroadcasterForNextViewer()
            }
            return
        }

        Log.d(TAG, "Resuming Broadcast from Standby (fromRemote=$fromRemote)")
        localCandidates = 0
        remoteCandidates = 0
        reconnectAttempts = 0
        hasReconnectedOnce = false
        _callDurationSeconds.value = 0L
        currentSessionId = UUID.randomUUID().toString().take(8)
        _callState.value = CallState.WaitingForPeer(label)
        signalingClient.updateDevicePresence(status = "broadcasting")
        startDurationTimer()

        val preferBack = !_isFrontCamera.value
        val enableAudio = broadcastAudioAllowed && _isMicEnabled.value

        rtc.resumeBroadcastFromStandby(
            preferBackCamera = preferBack,
            enableAudio = enableAudio
        ) { sdpOffer ->
            signalingClient.startBroadcast(
                deviceId = myDeviceId,
                sdpOffer = sdpOffer.description,
                sessionId = currentSessionId,
                isFrontCamera = _isFrontCamera.value,
                isMicEnabled = _isMicEnabled.value
            )
        }

        if (fromRemote) {
            showTransientBanner("Broadcast started remotely")
        }
    }

    private fun handleRemoteCommand(command: RemoteCommand) {
        Log.d(TAG, "Executing remote command: ${command.action} (mode=${_surveillanceMode.value})")

        // Zero-manual-start wake: if a Viewer taps this device while on Home or Device List!
        if (command.action == RemoteCommandAction.START_BROADCAST && _surveillanceMode.value != SurveillanceMode.BROADCAST) {
            if (_surveillanceMode.value == SurveillanceMode.VIEW) {
                // Stop viewing first if this device was viewing someone else
                stopSession(returnToDeviceList = false)
            }
            val app = getApplication<Application>()
            val hasCamera = ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val hasAudio = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (hasCamera) {
                startBroadcast(enableAudio = hasAudio, fromRemote = true)
            } else {
                _pendingRemoteBroadcastWake.value = true
            }
            return
        }

        if (_surveillanceMode.value != SurveillanceMode.BROADCAST) return

        when (command.action) {
            RemoteCommandAction.SWITCH_CAMERA -> {
                if (_callState.value is CallState.Standby) {
                    val nextFront = !_isFrontCamera.value
                    _isFrontCamera.value = nextFront
                    signalingClient.updateMediaState(
                        isCameraEnabled = false,
                        isMicEnabled = false,
                        isFrontCamera = nextFront,
                        broadcastActive = false
                    )
                    showTransientBanner("Remote: Camera set to ${if (nextFront) "Front" else "Rear"}")
                } else {
                    webRtcClient?.switchCamera { isFront ->
                        _isFrontCamera.value = isFront
                        signalingClient.updateMediaState(
                            isCameraEnabled = _isCameraEnabled.value,
                            isMicEnabled = _isMicEnabled.value,
                            isFrontCamera = isFront,
                            broadcastActive = true
                        )
                        showTransientBanner("Remote: Switched to ${if (isFront) "Front" else "Rear"} Camera")
                    }
                }
            }
            RemoteCommandAction.SET_MIC -> {
                val targetEnabled = command.value && broadcastAudioAllowed
                if (_callState.value is CallState.Standby) {
                    _isMicEnabled.value = targetEnabled
                    signalingClient.updateMediaState(
                        isCameraEnabled = false,
                        isMicEnabled = targetEnabled,
                        isFrontCamera = _isFrontCamera.value,
                        broadcastActive = false
                    )
                } else {
                    val enabled = webRtcClient?.toggleMicrophone(targetEnabled) ?: targetEnabled
                    _isMicEnabled.value = enabled
                    signalingClient.updateMediaState(
                        isCameraEnabled = _isCameraEnabled.value,
                        isMicEnabled = enabled,
                        isFrontCamera = _isFrontCamera.value,
                        broadcastActive = true
                    )
                }
                showTransientBanner(if (targetEnabled) "Remote: Mic Unmuted" else "Remote: Mic Muted")
            }
            RemoteCommandAction.STOP_BROADCAST -> {
                enterBroadcasterStandby(fromRemote = true)
            }
            RemoteCommandAction.START_BROADCAST -> {
                resumeBroadcasterFromStandby(fromRemote = true)
            }
        }
    }

    private fun resetBroadcasterForNextViewer() {
        val label = _deviceName.value
        val rtc = webRtcClient ?: return
        if (_surveillanceMode.value != SurveillanceMode.BROADCAST) return
        if (_callState.value is CallState.Standby) return

        Log.d(TAG, "Viewer left. Refreshing broadcast offer for next viewer...")
        reconnectJob?.cancel()
        disconnectDebounceJob?.cancel()
        stopStatsPolling()

        localCandidates = 0
        remoteCandidates = 0
        reconnectAttempts = 0
        hasReconnectedOnce = false
        currentSessionId = UUID.randomUUID().toString().take(8)
        _callState.value = CallState.WaitingForPeer(label)
        signalingClient.updateDevicePresence(status = "broadcasting")

        rtc.rebuildPeerConnection(SurveillanceRole.BROADCASTER) { sdpOffer ->
            signalingClient.startBroadcast(
                deviceId = myDeviceId,
                sdpOffer = sdpOffer.description,
                sessionId = currentSessionId,
                isFrontCamera = _isFrontCamera.value,
                isMicEnabled = _isMicEnabled.value
            )
        }
    }

    private fun handleSignalingEvent(event: SignalingEvent) {
        when (event) {
            is SignalingEvent.OnlineDevicesUpdated -> {
                val others = event.devices.filter { it.deviceId != myDeviceId }
                _onlineDevices.value = others
            }
            is SignalingEvent.RemoteCommandReceived -> {
                handleRemoteCommand(event.command)
            }
            is SignalingEvent.BroadcastStandby -> {
                if (_surveillanceMode.value == SurveillanceMode.VIEW) {
                    if (remoteRecorder?.isRecording == true) {
                        remoteRecorder?.stopRecording()
                    }
                    _remoteVideoTrack.value = null
                    _isRemoteBroadcastActive.value = false
                    stopDurationTimer()
                    stopStatsPolling()
                    reconnectJob?.cancel()
                    disconnectDebounceJob?.cancel()
                    lastViewerSessionId = ""
                    _callState.value = CallState.Standby(_targetDeviceName.value)
                    webRtcClient?.rebuildPeerConnection(SurveillanceRole.VIEWER)
                }
            }
            is SignalingEvent.OfferReceived -> {
                if (_surveillanceMode.value == SurveillanceMode.VIEW) {
                    viewerAutoWakeJob?.cancel()
                    val rtc = webRtcClient ?: return
                    val isNewBroadcastSession = lastViewerSessionId.isNotBlank() &&
                        event.sessionId.isNotBlank() &&
                        event.sessionId != lastViewerSessionId

                    if (isNewBroadcastSession) {
                        localCandidates = 0
                        remoteCandidates = 0
                        rtc.rebuildPeerConnection(SurveillanceRole.VIEWER)
                    }
                    lastViewerSessionId = event.sessionId
                    _isRemoteBroadcastActive.value = true

                    if (_callState.value is CallState.Standby) {
                        _callState.value = CallState.JoiningCall(_targetDeviceName.value)
                    }

                    rtc.setRemoteDescription(event.sdp, SessionDescription.Type.OFFER) {
                        rtc.createAnswer { answerDesc ->
                            signalingClient.sendAnswer(answerDesc.description)
                        }
                    }
                }
            }
            is SignalingEvent.AnswerReceived -> {
                if (_surveillanceMode.value == SurveillanceMode.BROADCAST && _callState.value !is CallState.Standby) {
                    webRtcClient?.setRemoteDescription(event.sdp, SessionDescription.Type.ANSWER) {
                        Log.d(TAG, "Viewer Answer applied successfully")
                    }
                }
            }
            is SignalingEvent.IceCandidateReceived -> {
                if (_callState.value is CallState.Standby) return
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
                    if (remoteRecorder?.isRecording == true) {
                        remoteRecorder?.stopRecording()
                    }
                    _remoteVideoTrack.value = null
                    _isRemoteBroadcastActive.value = false
                    stopDurationTimer()
                    stopStatsPolling()
                    reconnectJob?.cancel()
                    lastViewerSessionId = ""
                    _callState.value = CallState.Standby(_targetDeviceName.value)
                    webRtcClient?.rebuildPeerConnection(SurveillanceRole.VIEWER)
                }
            }
            is SignalingEvent.PeerMediaStateChanged -> {
                _isRemoteCameraEnabled.value = event.isCameraEnabled
                _isRemoteMicEnabled.value = event.isMicEnabled
                _isRemoteFrontCamera.value = event.isFrontCamera
                _isRemoteBroadcastActive.value = event.broadcastActive
            }
            is SignalingEvent.ReconnectRequested -> {
                val label = _targetDeviceName.value
                if (_surveillanceMode.value == SurveillanceMode.VIEW && _callState.value !is CallState.Standby) {
                    reconnectAttempts++
                    val mode = if (reconnectAttempts <= 3) ReconnectMode.RESUME else ReconnectMode.FULL_RECONNECT
                    updateStats()
                    if (mode == ReconnectMode.FULL_RECONNECT) {
                        _remoteVideoTrack.value = null
                        if (!hasReconnectedOnce) {
                            hasReconnectedOnce = true
                            _callState.value = CallState.Reconnecting(
                                deviceName = label,
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
        if (_callState.value is CallState.Standby) return

        val displayLabel = if (_surveillanceMode.value == SurveillanceMode.BROADCAST) {
            _deviceName.value
        } else {
            _targetDeviceName.value
        }

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
                        reconnectJob?.cancel()
                        reconnectAttempts = 0
                        hasReconnectedOnce = false
                        _isRemoteBroadcastActive.value = true
                        if (_callState.value !is CallState.Connected) {
                            _callState.value = CallState.Connected(displayLabel, _callDurationSeconds.value)
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
                        reconnectJob?.cancel()
                        reconnectAttempts = 0
                        hasReconnectedOnce = false
                        _isRemoteBroadcastActive.value = true
                        if (_callState.value !is CallState.Connected) {
                            _callState.value = CallState.Connected(displayLabel, _callDurationSeconds.value)
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
        if (_currentRoomId.value == null) return
        if (_callState.value is CallState.Standby) return

        val displayLabel = if (_surveillanceMode.value == SurveillanceMode.BROADCAST) {
            _deviceName.value
        } else {
            _targetDeviceName.value
        }
        val role = if (_surveillanceMode.value == SurveillanceMode.BROADCAST) {
            SurveillanceRole.BROADCASTER
        } else {
            SurveillanceRole.VIEWER
        }

        reconnectAttempts++
        val mode = if (reconnectAttempts <= 3) ReconnectMode.RESUME else ReconnectMode.FULL_RECONNECT
        updateStats()

        if (mode == ReconnectMode.FULL_RECONNECT) {
            _remoteVideoTrack.value = null
            if (!hasReconnectedOnce) {
                hasReconnectedOnce = true
                _callState.value = CallState.Reconnecting(
                    deviceName = displayLabel,
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
                    resetBroadcasterForNextViewer()
                } else {
                    _callState.value = CallState.Error("Connection lost after 30 attempts. Tap a device to reconnect.")
                }
                return@launch
            }

            delay(delayDuration.inWholeMilliseconds)
            if (!isActive || _callState.value is CallState.Standby) return@launch
            if (mode == ReconnectMode.FULL_RECONNECT && _callState.value !is CallState.Reconnecting) return@launch

            when (mode) {
                ReconnectMode.RESUME -> {
                    if (role == SurveillanceRole.BROADCASTER) {
                        webRtcClient?.restartIce(isBroadcaster = true) { sdpOffer ->
                            signalingClient.sendReconnectOffer(sdpOffer.description, currentSessionId)
                        }
                    } else {
                        signalingClient.updateStatus("reconnecting")
                    }
                }
                ReconnectMode.FULL_RECONNECT -> {
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
            if (isActive && _callState.value !is CallState.Standby &&
                (_callState.value is CallState.Reconnecting || (_callState.value is CallState.Connected && mode == ReconnectMode.RESUME))
            ) {
                if (reconnectAttempts < 30) {
                    val nextMode = if (reconnectAttempts + 1 <= 3) "Resume" else "Full Reconnect"
                    triggerReconnection("$nextMode retry #${reconnectAttempts + 1}")
                } else if (role == SurveillanceRole.BROADCASTER) {
                    resetBroadcasterForNextViewer()
                } else {
                    _callState.value = CallState.Error("Connection lost after 30 attempts. Tap a device to reconnect.")
                }
            }
        }
    }

    fun forceReconnect() {
        val targetId = _currentRoomId.value ?: return
        if (_surveillanceMode.value == SurveillanceMode.VIEW &&
            (_callState.value is CallState.JoiningCall || _callState.value is CallState.Standby)
        ) {
            signalingClient.joinAsViewer(targetId)
            signalingClient.sendRemoteCommand(RemoteCommandAction.START_BROADCAST, targetDeviceId = targetId)
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
                val isBroadcaster = _surveillanceMode.value == SurveillanceMode.BROADCAST
                val label = if (isBroadcaster) _deviceName.value else _targetDeviceName.value
                CallForegroundService.updateService(getApplication(), isBroadcaster, label, durationText)
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

    // Broadcaster Local Controls
    fun toggleMicrophone() {
        webRtcClient?.let {
            val enabled = it.toggleMicrophone()
            _isMicEnabled.value = enabled
            signalingClient.updateMediaState(
                isCameraEnabled = _isCameraEnabled.value,
                isMicEnabled = enabled,
                isFrontCamera = _isFrontCamera.value,
                broadcastActive = _callState.value !is CallState.Standby
            )
        }
    }

    fun toggleCamera() {
        webRtcClient?.let {
            val enabled = it.toggleCamera()
            _isCameraEnabled.value = enabled
            signalingClient.updateMediaState(
                isCameraEnabled = enabled,
                isMicEnabled = _isMicEnabled.value,
                isFrontCamera = _isFrontCamera.value,
                broadcastActive = _callState.value !is CallState.Standby
            )
        }
    }

    fun switchCamera() {
        webRtcClient?.switchCamera { isFront ->
            _isFrontCamera.value = isFront
            signalingClient.updateMediaState(
                isCameraEnabled = _isCameraEnabled.value,
                isMicEnabled = _isMicEnabled.value,
                isFrontCamera = isFront,
                broadcastActive = _callState.value !is CallState.Standby
            )
        }
    }

    fun toggleStealthMode(enabled: Boolean? = null) {
        _isStealthMode.value = enabled ?: !_isStealthMode.value
    }

    // Viewer Local Monitor Controls
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
            val safeName = _targetDeviceName.value.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Device" }
            rec.startRecording(track, safeName, webRtcClient)
        }
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

    fun stopSession(returnToDeviceList: Boolean = false) {
        CallForegroundService.stopService(getApplication())

        if (remoteRecorder?.isRecording == true) {
            remoteRecorder?.stopRecording()
        }

        val wasMode = _surveillanceMode.value
        val currentState = _callState.value
        when (wasMode) {
            SurveillanceMode.BROADCAST -> signalingClient.stopBroadcast()
            SurveillanceMode.VIEW -> {
                if (currentState is CallState.Standby) {
                    signalingClient.cleanup()
                } else {
                    signalingClient.leaveAsViewer()
                }
            }
            else -> signalingClient.cleanup()
        }

        stopDurationTimer()
        stopStatsPolling()
        reconnectJob?.cancel()
        disconnectDebounceJob?.cancel()
        viewerAutoWakeJob?.cancel()
        bannerClearJob?.cancel()
        cancelWebRtcObservers()

        webRtcClient?.close()
        webRtcClient = null

        if (wasMode == SurveillanceMode.VIEW) {
            audioManager.stop()
        }

        hasReconnectedOnce = false
        _isStealthMode.value = false
        _remoteCommandBanner.value = null
        _callState.value = CallState.Idle
        _currentRoomId.value = null
        _callDurationSeconds.value = 0L
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        _liveStats.value = WebRtcLiveStats()

        if (returnToDeviceList && wasMode == SurveillanceMode.VIEW) {
            _surveillanceMode.value = SurveillanceMode.DEVICE_LIST
            signalingClient.updateDevicePresence(status = "ready")
            signalingClient.startObservingOnlineDevices()
        } else {
            _surveillanceMode.value = SurveillanceMode.NONE
            signalingClient.updateDevicePresence(status = "ready")
        }
    }

    fun resetToIdle() {
        _callState.value = CallState.Idle
        _surveillanceMode.value = SurveillanceMode.NONE
        _currentRoomId.value = null
        _callDurationSeconds.value = 0L
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        _isStealthMode.value = false
        _remoteCommandBanner.value = null
        signalingClient.updateDevicePresence(status = "ready")
    }

    override fun onCleared() {
        super.onCleared()
        if (activeInstance == this) {
            activeInstance = null
        }
        stopSession(returnToDeviceList = false)
        signalingClient.markDeviceOffline()
    }
}
