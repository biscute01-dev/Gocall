package com.example.auth

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

sealed class AuthUiState {
    object Loading : AuthUiState()
    object Unauthenticated : AuthUiState()
    data class NeedsProfileSetup(
        val firebaseUser: FirebaseUser,
        val suggestedDisplayName: String,
        val suggestedPhotoUrl: String?
    ) : AuthUiState()
    data class Authenticated(val profile: UserProfile) : AuthUiState()
}

class AuthRepository(
    private val context: Context,
    customRtdbUrl: String? = null
) {
    companion object {
        private const val TAG = "AuthRepository"
        // Default Web Client ID for Google Sign In from google-services.json
        const val WEB_CLIENT_ID = "914908620155-l2sb6dekom0l61v7b61b7fl7452hal4s.apps.googleusercontent.com"
        private const val NODE_USERS = "users"
        private const val NODE_USERNAMES = "usernames"
    }

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val database: FirebaseDatabase = if (!customRtdbUrl.isNullOrBlank()) {
        FirebaseDatabase.getInstance(customRtdbUrl)
    } else {
        FirebaseDatabase.getInstance()
    }

    private val credentialManager: CredentialManager = CredentialManager.create(context)

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _currentUserProfile = MutableStateFlow<UserProfile?>(null)
    val currentUserProfile: StateFlow<UserProfile?> = _currentUserProfile.asStateFlow()

    private var profileListener: ValueEventListener? = null

    init {
        // Observe auth state changes
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user == null) {
                detachProfileListener()
                _currentUserProfile.value = null
                _authState.value = AuthUiState.Unauthenticated
            } else {
                listenToUserProfile(user)
            }
        }
    }

    private fun listenToUserProfile(user: FirebaseUser) {
        detachProfileListener()
        val userRef = database.getReference(NODE_USERS).child(user.uid)
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val profile = snapshot.getValue(UserProfile::class.java)
                    if (profile != null && profile.username.isNotBlank()) {
                        _currentUserProfile.value = profile
                        _authState.value = AuthUiState.Authenticated(profile)
                        syncFcmToken(user.uid)
                    } else {
                        // User exists in Auth but hasn't finalized their unique username
                        _currentUserProfile.value = null
                        _authState.value = AuthUiState.NeedsProfileSetup(
                            firebaseUser = user,
                            suggestedDisplayName = user.displayName ?: "",
                            suggestedPhotoUrl = user.photoUrl?.toString()
                        )
                    }
                } else {
                    // New user needing profile setup
                    _currentUserProfile.value = null
                    _authState.value = AuthUiState.NeedsProfileSetup(
                        firebaseUser = user,
                        suggestedDisplayName = user.displayName ?: "",
                        suggestedPhotoUrl = user.photoUrl?.toString()
                    )
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error fetching user profile: ${error.message}")
            }
        }
        
        profileListener = listener
        userRef.addValueEventListener(listener)
    }

    private fun detachProfileListener() {
        val user = auth.currentUser
        if (user != null && profileListener != null) {
            database.getReference(NODE_USERS).child(user.uid).removeEventListener(profileListener!!)
            profileListener = null
        }
    }

    /**
     * Sign in using Android Credential Manager with Google ID Token.
     */
    suspend fun signInWithGoogle(activity: Activity): Result<UserProfile?> = withContext(Dispatchers.IO) {
        try {
            val rawNonce = UUID.randomUUID().toString()
            val bytes = rawNonce.toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            val hashedNonce = digest.fold("") { str, it -> str + "%02x".format(it) }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .setNonce(hashedNonce)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = withContext(Dispatchers.Main) {
                credentialManager.getCredential(
                    request = request,
                    context = activity
                )
            }

            val credential = result.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(authCredential).await()
                val firebaseUser = authResult.user ?: throw Exception("Firebase user is null after sign in")

                // Check if profile exists
                val snapshot = database.getReference(NODE_USERS).child(firebaseUser.uid).get().await()
                val profile = snapshot.getValue(UserProfile::class.java)

                Result.success(profile)
            } else {
                Result.failure(Exception("Unsupported credential type received"))
            }
        } catch (e: GetCredentialCancellationException) {
            Result.failure(Exception("Sign-in cancelled by user"))
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager error: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Check if a given username is available (not taken by another UID).
     */
    suspend fun checkUsernameAvailable(username: String, currentUid: String? = null): Boolean = withContext(Dispatchers.IO) {
        val sanitized = username.trim().lowercase().removePrefix("@")
        if (sanitized.length < 3) return@withContext false

        try {
            val snapshot = database.getReference(NODE_USERNAMES).child(sanitized).get().await()
            if (!snapshot.exists()) {
                true
            } else {
                val ownerUid = snapshot.getValue(String::class.java)
                ownerUid == null || ownerUid == currentUid
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check username availability: ${e.message}")
            false
        }
    }

    /**
     * Complete or update user profile with chosen display name, unique username, and avatar image.
     */
    suspend fun saveUserProfile(
        displayName: String,
        username: String,
        avatarUri: Uri?,
        keepExistingAvatar: Boolean = true
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext Result.failure(Exception("Not logged in"))
        val sanitizedUsername = username.trim().lowercase().removePrefix("@")

        if (sanitizedUsername.length < 3 || sanitizedUsername.length > 20) {
            return@withContext Result.failure(Exception("Username must be between 3 and 20 characters"))
        }
        if (!sanitizedUsername.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
            return@withContext Result.failure(Exception("Username can only contain letters, numbers, underscores and dots"))
        }

        // Check availability
        val isAvailable = checkUsernameAvailable(sanitizedUsername, user.uid)
        if (!isAvailable) {
            return@withContext Result.failure(Exception("Username '@$sanitizedUsername' is already taken"))
        }

        try {
            // Get existing profile to release old username if changed
            val currentSnapshot = database.getReference(NODE_USERS).child(user.uid).get().await()
            val existingProfile = currentSnapshot.getValue(UserProfile::class.java)
            val oldUsername = existingProfile?.username?.lowercase()

            var avatarBase64: String? = existingProfile?.avatarBase64
            var localPhotoPath: String? = existingProfile?.localPhotoUri

            // Process new avatar if provided
            if (avatarUri != null) {
                val processed = processAvatarUri(context, user.uid, avatarUri)
                avatarBase64 = processed.base64
                localPhotoPath = processed.localPath
            } else if (!keepExistingAvatar) {
                avatarBase64 = null
                localPhotoPath = null
            }

            val finalPhotoUrl = user.photoUrl?.toString()

            val updatedProfile = UserProfile(
                uid = user.uid,
                displayName = displayName.trim().ifBlank { user.displayName ?: sanitizedUsername },
                username = sanitizedUsername,
                email = user.email ?: "",
                photoUrl = finalPhotoUrl,
                avatarBase64 = avatarBase64,
                localPhotoUri = localPhotoPath,
                createdAt = existingProfile?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            // Atomic-like update in RTDB
            val updates = hashMapOf<String, Any?>()
            updates["$NODE_USERS/${user.uid}"] = updatedProfile
            updates["$NODE_USERNAMES/$sanitizedUsername"] = user.uid

            // Remove old username if changed
            if (!oldUsername.isNullOrBlank() && oldUsername != sanitizedUsername) {
                updates["$NODE_USERNAMES/$oldUsername"] = null
            }

            database.reference.updateChildren(updates).await()

            // Update Firebase Auth user display name
            try {
                user.updateProfile(
                    UserProfileChangeRequest.Builder()
                        .setDisplayName(updatedProfile.displayName)
                        .build()
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "Could not update Firebase Auth profile display name: ${e.message}")
            }

            _currentUserProfile.value = updatedProfile
            _authState.value = AuthUiState.Authenticated(updatedProfile)

            Result.success(updatedProfile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user profile: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Sign out user from Firebase Auth and Credential Manager.
     */
    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        try {
            detachProfileListener()
            auth.signOut()
            try {
                credentialManager.clearCredentialState(
                    androidx.credentials.ClearCredentialStateRequest()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Credential clear error: ${e.message}")
            }
            _currentUserProfile.value = null
            _authState.value = AuthUiState.Unauthenticated
        } catch (e: Exception) {
            Log.e(TAG, "Sign out error: ${e.message}", e)
        }
    }

    private data class ProcessedAvatar(val base64: String?, val localPath: String?)

    private fun processAvatarUri(context: Context, uid: String, uri: Uri): ProcessedAvatar {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                // Scale bitmap down to maximum 256x256 for optimal storage and rendering
                val maxDim = 256
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val targetW = if (ratio > 1f) maxDim else (maxDim * ratio).toInt().coerceAtLeast(64)
                val targetH = if (ratio > 1f) (maxDim / ratio).toInt().coerceAtLeast(64) else maxDim
                val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

                // Save locally to internal storage
                val avatarDir = File(context.filesDir, "avatars").apply { mkdirs() }
                val localFile = File(avatarDir, "avatar_${uid}.jpg")
                FileOutputStream(localFile).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }

                // Create compressed Base64 string for database sync
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                val base64String = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                ProcessedAvatar(base64 = base64String, localPath = localFile.absolutePath)
            } else {
                ProcessedAvatar(base64 = null, localPath = null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process avatar uri: ${e.message}", e)
            ProcessedAvatar(base64 = null, localPath = null)
        }
    }

    private fun syncFcmToken(uid: String) {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        database.getReference(NODE_USERS).child(uid).child("fcmToken").setValue(token)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve FCM token: ${e.message}")
        }
    }
}
