package com.example.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.auth.AuthRepository
import com.example.auth.AuthUiState
import com.example.auth.UserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UsernameStatus {
    object Idle : UsernameStatus()
    object Checking : UsernameStatus()
    object Available : UsernameStatus()
    object Taken : UsernameStatus()
    data class Invalid(val reason: String) : UsernameStatus()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val PREFS_NAME = "video_call_prefs"
        private const val KEY_CUSTOM_RTDB = "custom_rtdb_url"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val repository = AuthRepository(
        context = application,
        customRtdbUrl = prefs.getString(KEY_CUSTOM_RTDB, null)
    )

    val authState: StateFlow<AuthUiState> = repository.authState
    val currentUserProfile: StateFlow<UserProfile?> = repository.currentUserProfile

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _isSavingProfile = MutableStateFlow(false)
    val isSavingProfile: StateFlow<Boolean> = _isSavingProfile.asStateFlow()

    private val _usernameStatus = MutableStateFlow<UsernameStatus>(UsernameStatus.Idle)
    val usernameStatus: StateFlow<UsernameStatus> = _usernameStatus.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private var usernameCheckJob: Job? = null

    fun signInWithGoogle(activity: Activity) {
        if (_isSigningIn.value) return
        _isSigningIn.value = true
        _authError.value = null

        viewModelScope.launch {
            val result = repository.signInWithGoogle(activity)
            _isSigningIn.value = false
            result.onFailure { error ->
                if (error.message?.contains("cancelled", ignoreCase = true) != true) {
                    _authError.value = error.message ?: "Failed to sign in with Google"
                }
            }
        }
    }

    fun validateAndCheckUsername(username: String, currentUid: String? = null) {
        usernameCheckJob?.cancel()
        val sanitized = username.trim().lowercase().removePrefix("@")

        if (sanitized.isEmpty()) {
            _usernameStatus.value = UsernameStatus.Idle
            return
        }

        if (sanitized.length < 3) {
            _usernameStatus.value = UsernameStatus.Invalid("Must be at least 3 characters")
            return
        }

        if (sanitized.length > 20) {
            _usernameStatus.value = UsernameStatus.Invalid("Maximum 20 characters")
            return
        }

        if (!sanitized.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
            _usernameStatus.value = UsernameStatus.Invalid("Letters, numbers, '.', and '_' only")
            return
        }

        _usernameStatus.value = UsernameStatus.Checking
        usernameCheckJob = viewModelScope.launch {
            delay(350) // Debounce user typing
            val available = repository.checkUsernameAvailable(sanitized, currentUid)
            _usernameStatus.value = if (available) UsernameStatus.Available else UsernameStatus.Taken
        }
    }

    fun completeProfile(
        displayName: String,
        username: String,
        avatarUri: Uri?,
        onSuccess: () -> Unit
    ) {
        if (_isSavingProfile.value) return
        _isSavingProfile.value = true
        _authError.value = null

        viewModelScope.launch {
            val result = repository.saveUserProfile(
                displayName = displayName,
                username = username,
                avatarUri = avatarUri
            )
            _isSavingProfile.value = false
            result.onSuccess {
                onSuccess()
            }.onFailure { error ->
                _authError.value = error.message ?: "Failed to save profile"
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOut()
        }
    }

    fun clearError() {
        _authError.value = null
    }
}
