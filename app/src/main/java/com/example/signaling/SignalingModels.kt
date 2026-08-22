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
data class RoomData(
    val caller: SessionDescriptionModel? = null,
    val callee: SessionDescriptionModel? = null,
    val callerCandidates: Map<String, IceCandidateModel>? = null,
    val calleeCandidates: Map<String, IceCandidateModel>? = null,
    val status: String = "open",
    val createdAt: Long = System.currentTimeMillis()
)

@Keep
data class MediaStateModel(
    val cameraEnabled: Boolean = true,
    val micEnabled: Boolean = true
)

sealed class SignalingEvent {
    data class OfferReceived(val sdp: String) : SignalingEvent()
    data class AnswerReceived(val sdp: String) : SignalingEvent()
    data class IceCandidateReceived(val candidate: IceCandidateModel, val isCallerCandidate: Boolean) : SignalingEvent()
    data class PeerDisconnected(val reason: String) : SignalingEvent()
    data class ReconnectRequested(val timestamp: Long) : SignalingEvent()
    data class PeerMediaStateChanged(val isCameraEnabled: Boolean, val isMicEnabled: Boolean) : SignalingEvent()
    data class Error(val message: String) : SignalingEvent()
}
