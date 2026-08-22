package com.example.auth

import androidx.annotation.Keep

@Keep
data class FriendUser(
    val uid: String = "",
    val displayName: String = "",
    val username: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val avatarBase64: String? = null,
    val localPhotoUri: String? = null,
    val fcmToken: String? = null,
    val isOnline: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val addedAt: Long = System.currentTimeMillis()
)

@Keep
data class FriendRequest(
    val id: String = "",
    val senderUid: String = "",
    val senderDisplayName: String = "",
    val senderUsername: String = "",
    val senderPhotoUrl: String? = null,
    val senderAvatarBase64: String? = null,
    val receiverUid: String = "",
    val receiverDisplayName: String = "",
    val receiverUsername: String = "",
    val receiverPhotoUrl: String? = null,
    val receiverAvatarBase64: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "pending" // "pending", "accepted", "rejected"
)

@Keep
data class CallInvitation(
    val callId: String = "",
    val callerUid: String = "",
    val callerDisplayName: String = "",
    val callerUsername: String = "",
    val callerPhotoUrl: String? = null,
    val callerAvatarBase64: String? = null,
    val calleeUid: String = "",
    val calleeDisplayName: String = "",
    val calleeUsername: String = "",
    val calleePhotoUrl: String? = null,
    val calleeAvatarBase64: String? = null,
    val status: String = "ringing", // "ringing", "accepted", "rejected", "cancelled", "ended"
    val timestamp: Long = System.currentTimeMillis()
)

enum class FriendRelationshipStatus {
    SELF,
    FRIENDS,
    REQUEST_SENT,
    REQUEST_RECEIVED,
    NOT_FRIENDS
}

data class UserSearchResult(
    val user: UserProfile,
    val relationship: FriendRelationshipStatus
)
