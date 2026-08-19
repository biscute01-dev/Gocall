package com.example.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

sealed class WebRtcEvent {
    data class IceCandidateGenerated(val candidate: IceCandidate) : WebRtcEvent()
    data class RemoteTrackReceived(val videoTrack: VideoTrack?) : WebRtcEvent()
    data class IceStateChanged(val state: PeerConnection.IceConnectionState) : WebRtcEvent()
    data class ConnectionStateChanged(val state: PeerConnection.PeerConnectionState) : WebRtcEvent()
    data class Error(val message: String) : WebRtcEvent()
}

class WebRtcClient(private val context: Context) {
    companion object {
        private const val TAG = "WebRtcClient"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
    }

    val rootEglBase: EglBase = EglBase.create()
    private val scope = CoroutineScope(Dispatchers.Default)

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val _events = MutableSharedFlow<WebRtcEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WebRtcEvent> = _events.asSharedFlow()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(true)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(true)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _iceConnectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val iceConnectionState: StateFlow<PeerConnection.IceConnectionState> = _iceConnectionState.asStateFlow()

    private val _connectionState = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = _connectionState.asStateFlow()

    // Google Public STUN Servers
    private val googleStunServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
    )

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            rootEglBase.eglBaseContext,
            true, // enableIntelVp8Encoder
            true  // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun startLocalMedia() {
        val factory = peerConnectionFactory ?: return

        // Audio track setup
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(_isMicEnabled.value)

        // Video track setup
        val enumerator = Camera2Enumerator(context)
        videoCapturer = createCameraCapturer(enumerator)

        videoCapturer?.let { capturer ->
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
            videoSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            try {
                capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera capture: ${e.message}")
            }
            localVideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)
            localVideoTrack?.setEnabled(_isCameraEnabled.value)
        }
    }

    fun initLocalSurfaceView(renderer: SurfaceViewRenderer) {
        try {
            renderer.init(rootEglBase.eglBaseContext, null)
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(true)
            localVideoTrack?.addSink(renderer)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing local renderer: ${e.message}")
        }
    }

    fun initRemoteSurfaceView(renderer: SurfaceViewRenderer) {
        try {
            renderer.init(rootEglBase.eglBaseContext, null)
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(false)
            _remoteVideoTrack.value?.addSink(renderer)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing remote renderer: ${e.message}")
        }
    }

    fun attachRemoteSink(renderer: SurfaceViewRenderer) {
        _remoteVideoTrack.value?.addSink(renderer)
    }

    fun detachRemoteSink(renderer: SurfaceViewRenderer) {
        _remoteVideoTrack.value?.removeSink(renderer)
    }

    fun createPeerConnection() {
        val factory = peerConnectionFactory ?: return

        val rtcConfig = PeerConnection.RTCConfiguration(googleStunServers).apply {
            iceCandidatePoolSize = 10
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: $state")
                _iceConnectionState.value = state
                scope.launch {
                    _events.emit(WebRtcEvent.IceStateChanged(state))
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "onIceCandidate: ${candidate.sdpMid} ${candidate.sdpMLineIndex}")
                scope.launch {
                    _events.emit(WebRtcEvent.IceCandidateGenerated(candidate))
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "onIceCandidatesRemoved")
            }

            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "onAddStream: ${stream.videoTracks.size} video tracks")
                if (stream.videoTracks.isNotEmpty()) {
                    val track = stream.videoTracks[0]
                    _remoteVideoTrack.value = track
                    scope.launch {
                        _events.emit(WebRtcEvent.RemoteTrackReceived(track))
                    }
                }
            }

            override fun onRemoveStream(stream: MediaStream) {
                Log.d(TAG, "onRemoveStream")
                _remoteVideoTrack.value = null
            }

            override fun onDataChannel(dataChannel: org.webrtc.DataChannel) {}

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded")
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "onAddTrack video track received")
                    _remoteVideoTrack.value = track
                    scope.launch {
                        _events.emit(WebRtcEvent.RemoteTrackReceived(track))
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "onTrack video track received")
                    _remoteVideoTrack.value = track
                    scope.launch {
                        _events.emit(WebRtcEvent.RemoteTrackReceived(track))
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "onConnectionChange: $newState")
                _connectionState.value = newState
                scope.launch {
                    _events.emit(WebRtcEvent.ConnectionStateChanged(newState))
                }
            }
        })

        // Add local tracks to PeerConnection
        val pc = peerConnection ?: return
        localAudioTrack?.let {
            pc.addTrack(it, listOf("ARDAMS"))
        }
        localVideoTrack?.let {
            pc.addTrack(it, listOf("ARDAMS"))
        }
    }

    fun createOffer(onSdpCreated: (SessionDescription) -> Unit, iceRestart: Boolean = false) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        onSdpCreated(desc)
                    }
                    override fun onCreateFailure(err: String?) {
                        Log.e(TAG, "setLocalDescription createFailure: $err")
                    }
                    override fun onSetFailure(err: String?) {
                        Log.e(TAG, "setLocalDescription setFailure: $err")
                    }
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "createOffer onCreateFailure: $err")
                scope.launch { _events.emit(WebRtcEvent.Error("Offer creation failed: $err")) }
            }
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    fun createAnswer(onSdpCreated: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        onSdpCreated(desc)
                    }
                    override fun onCreateFailure(err: String?) {
                        Log.e(TAG, "setLocalDescription (answer) failure: $err")
                    }
                    override fun onSetFailure(err: String?) {
                        Log.e(TAG, "setLocalDescription (answer) setFailure: $err")
                    }
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "createAnswer onCreateFailure: $err")
                scope.launch { _events.emit(WebRtcEvent.Error("Answer creation failed: $err")) }
            }
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: String, type: SessionDescription.Type, onSetSuccess: () -> Unit = {}) {
        val sdpDesc = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "setRemoteDescription success ($type)")
                onSetSuccess()
            }
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "setRemoteDescription createFailure: $err")
            }
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "setRemoteDescription setFailure: $err")
                scope.launch { _events.emit(WebRtcEvent.Error("Remote description error: $err")) }
            }
        }, sdpDesc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun restartIce(onSdpCreated: (SessionDescription) -> Unit) {
        Log.d(TAG, "Initiating ICE Restart for reconnection...")
        createOffer(onSdpCreated, iceRestart = true)
    }

    fun toggleMicrophone(enabled: Boolean? = null): Boolean {
        val newState = enabled ?: !_isMicEnabled.value
        _isMicEnabled.value = newState
        localAudioTrack?.setEnabled(newState)
        return newState
    }

    fun toggleCamera(enabled: Boolean? = null): Boolean {
        val newState = enabled ?: !_isCameraEnabled.value
        _isCameraEnabled.value = newState
        localVideoTrack?.setEnabled(newState)
        return newState
    }

    fun switchCamera(onComplete: ((isFront: Boolean) -> Unit)? = null) {
        val capturer = videoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _isFrontCamera.value = isFrontCamera
                onComplete?.invoke(isFrontCamera)
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e(TAG, "onCameraSwitchError: $errorDescription")
            }
        })
    }

    private fun createCameraCapturer(enumerator: Camera2Enumerator): CameraVideoCapturer? {
        val deviceNames = enumerator.deviceNames
        // First try front-facing camera
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    _isFrontCamera.value = true
                    return capturer
                }
            }
        }
        // Fallback to back camera
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    _isFrontCamera.value = false
                    return capturer
                }
            }
        }
        return null
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capturer: ${e.message}")
        }
        videoCapturer?.dispose()
        videoCapturer = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null

        audioSource?.dispose()
        audioSource = null

        localVideoTrack?.setEnabled(false)
        localVideoTrack?.dispose()
        localVideoTrack = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        _remoteVideoTrack.value = null
        _iceConnectionState.value = PeerConnection.IceConnectionState.CLOSED
        _connectionState.value = PeerConnection.PeerConnectionState.CLOSED
    }
}
