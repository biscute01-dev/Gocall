package com.example.auth

import androidx.annotation.Keep

@Keep
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val username: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val avatarBase64: String? = null,
    val localPhotoUri: String? = null,
    val fcmToken: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
