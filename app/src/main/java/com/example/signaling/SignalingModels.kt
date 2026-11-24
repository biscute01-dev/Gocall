package com.example.signaling

import androidx.annotation.Keep

@Keep
data class SessionDescriptionModel(
    val sdp: String = "",
    val type: String = ""
)

@Keep
data class IceCandidateModel(
    val sdp: String = "",
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    val serverUrl: String? = null
)

@Keep
data class MediaStateModel(
    val cameraEnabled: Boolean = true,
    val micEnabled: Boolean = true,
    val isFrontCamera: Boolean = false,
    val broadcastActive: Boolean = true
)

@Keep
data class OnlineDevice(
    val deviceId: String = "",
    val deviceName: String = "",
    val online: Boolean = false,
    val status: String = "ready", // "ready", "broadcasting", "standby", "viewing", "offline"
    val lastSeen: Long = 0L
)

enum class RemoteCommandAction {
    SWITCH_CAMERA,
    SET_MIC,
    START_BROADCAST,
    STOP_BROADCAST
}

@Keep
data class RemoteCommand(
    val id: String = "",
    val action: RemoteCommandAction = RemoteCommandAction.SWITCH_CAMERA,
    val value: Boolean = false,
    val timestamp: Long = 0L
)

sealed class SignalingEvent {
    data class OfferReceived(val sdp: String, val sessionId: String = "", val isReconnect: Boolean = false) : SignalingEvent()
    data class AnswerReceived(val sdp: String) : SignalingEvent()
    data class IceCandidateReceived(val candidate: IceCandidateModel, val isCallerCandidate: Boolean) : SignalingEvent()
    data class PeerDisconnected(val reason: String) : SignalingEvent()
    data class ViewerLeft(val timestamp: Long) : SignalingEvent()
    data class ReconnectRequested(val timestamp: Long) : SignalingEvent()
    data class PeerMediaStateChanged(
        val isCameraEnabled: Boolean,
        val isMicEnabled: Boolean,
        val isFrontCamera: Boolean = false,
        val broadcastActive: Boolean = true
    ) : SignalingEvent()
    data class RemoteCommandReceived(val command: RemoteCommand) : SignalingEvent()
    data class OnlineDevicesUpdated(val devices: List<OnlineDevice>) : SignalingEvent()
    object BroadcastStandby : SignalingEvent()
    data class Error(val message: String) : SignalingEvent()
}
