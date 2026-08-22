package com.example

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.auth.AuthUiState
import com.example.ui.screens.CallScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.ProfileSetupScreen
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.SlateDark950
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.CallState
import com.example.viewmodel.CallViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val callViewModel: CallViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()

    companion object {
        const val ACTION_PIP_END_CALL = "com.example.action.PIP_END_CALL"
        const val ACTION_PIP_TOGGLE_MIC = "com.example.action.PIP_TOGGLE_MIC"
        const val ACTION_PIP_TOGGLE_SPEAKER = "com.example.action.PIP_TOGGLE_SPEAKER"
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_END_CALL -> {
                    callViewModel.endCall()
                }
                ACTION_PIP_TOGGLE_MIC -> {
                    callViewModel.toggleMicrophone()
                    updatePipParamsIfPossible()
                }
                ACTION_PIP_TOGGLE_SPEAKER -> {
                    callViewModel.toggleSpeaker()
                    updatePipParamsIfPossible()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_END_CALL)
            addAction(ACTION_PIP_TOGGLE_MIC)
            addAction(ACTION_PIP_TOGGLE_SPEAKER)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, filter)
        }

        // Keep PiP actions and auto-enter params in sync with ViewModel state
        lifecycleScope.launch {
            combine(
                callViewModel.callState,
                callViewModel.isMicEnabled,
                callViewModel.audioManager.isSpeakerOn
            ) { _, _, _ ->
                updatePipParamsIfPossible()
            }.collect {}
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SlateDark950
                ) {
                    MainApp(
                        viewModel = callViewModel,
                        authViewModel = authViewModel,
                        onEnterPip = { enterPipMode() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val state = callViewModel.callState.value
        val isInCall = state !is CallState.Idle && state !is CallState.Ended
        if (isInCall) {
            enterPipMode()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        callViewModel.setInPipMode(isInPictureInPictureMode)
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(buildPipParams())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updatePipParamsIfPossible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                setPictureInPictureParams(buildPipParams())
            } catch (e: Exception) {
                // Ignore if not supported or not ready
            }
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val isMicEnabled = callViewModel.isMicEnabled.value
        val isSpeakerOn = callViewModel.audioManager.isSpeakerOn.value

        val actions = mutableListOf<RemoteAction>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. Mic toggle action (Mute / Unmute)
            val micIntent = Intent(ACTION_PIP_TOGGLE_MIC).setPackage(packageName)
            val micPendingIntent = PendingIntent.getBroadcast(
                this, 101, micIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val micIconRes = if (isMicEnabled) R.drawable.ic_pip_mic_on else R.drawable.ic_pip_mic_off
            val micTitle = if (isMicEnabled) "Mute" else "Unmute"
            actions.add(
                RemoteAction(
                    Icon.createWithResource(this, micIconRes),
                    micTitle,
                    micTitle,
                    micPendingIntent
                )
            )

            // 2. Speaker toggle action (Speaker / Earpiece)
            val speakerIntent = Intent(ACTION_PIP_TOGGLE_SPEAKER).setPackage(packageName)
            val speakerPendingIntent = PendingIntent.getBroadcast(
                this, 102, speakerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val speakerIconRes = if (isSpeakerOn) R.drawable.ic_pip_speaker_on else R.drawable.ic_pip_speaker_off
            val speakerTitle = if (isSpeakerOn) "Speaker" else "Earpiece"
            actions.add(
                RemoteAction(
                    Icon.createWithResource(this, speakerIconRes),
                    speakerTitle,
                    speakerTitle,
                    speakerPendingIntent
                )
            )

            // 3. End Call action
            val endCallIntent = Intent(ACTION_PIP_END_CALL).setPackage(packageName)
            val endCallPendingIntent = PendingIntent.getBroadcast(
                this, 103, endCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            actions.add(
                RemoteAction(
                    Icon.createWithResource(this, R.drawable.ic_pip_call_end),
                    "End Call",
                    "End Call",
                    endCallPendingIntent
                )
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .setActions(actions)
        } else {
            error("PiP is only supported on Android 8.0+")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isInCall = callViewModel.callState.value !is CallState.Idle &&
                    callViewModel.callState.value !is CallState.Ended
            builder.setAutoEnterEnabled(isInCall)
        }

        return builder.build()
    }
}

@Composable
fun MainApp(
    viewModel: CallViewModel,
    authViewModel: AuthViewModel,
    onEnterPip: () -> Unit = {}
) {
    val authState by authViewModel.authState.collectAsState()
    val callState by viewModel.callState.collectAsState()
    var isEditingProfile by remember { mutableStateOf(false) }
    var showEndCallConfirmDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(callState) {
        when (val state = callState) {
            is CallState.Ended -> {
                // Return to home on call end
                viewModel.resetToIdle()
            }
            is CallState.Error -> {
                errorMessage = state.message
            }
            else -> {}
        }
    }

    val isInCall = callState !is CallState.Idle && callState !is CallState.Ended

    // Intercept back press when on active call or editing profile
    BackHandler(enabled = isInCall || isEditingProfile) {
        if (isInCall) {
            showEndCallConfirmDialog = true
        } else if (isEditingProfile) {
            isEditingProfile = false
        }
    }

    AnimatedContent(
        targetState = authState,
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        },
        label = "auth_and_app_navigation"
    ) { currentAuthState ->
        when (currentAuthState) {
            is AuthUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SlateDark950),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = CyanGlow,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
            is AuthUiState.Unauthenticated -> {
                LoginScreen(authViewModel = authViewModel)
            }
            is AuthUiState.NeedsProfileSetup -> {
                ProfileSetupScreen(
                    authViewModel = authViewModel,
                    suggestedDisplayName = currentAuthState.suggestedDisplayName,
                    suggestedPhotoUrl = currentAuthState.suggestedPhotoUrl,
                    onProfileCompleted = {
                        // Successfully completed profile
                    }
                )
            }
            is AuthUiState.Authenticated -> {
                if (isEditingProfile) {
                    ProfileSetupScreen(
                        authViewModel = authViewModel,
                        suggestedDisplayName = currentAuthState.profile.displayName,
                        suggestedPhotoUrl = currentAuthState.profile.photoUrl,
                        isEditMode = true,
                        existingProfile = currentAuthState.profile,
                        onProfileCompleted = {
                            isEditingProfile = false
                        }
                    )
                } else {
                    AnimatedContent(
                        targetState = isInCall,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "call_navigation"
                    ) { inCall ->
                        if (inCall) {
                            CallScreen(
                                viewModel = viewModel,
                                onEndCall = {
                                    viewModel.endCall()
                                },
                                onEnterPip = onEnterPip
                            )
                        } else {
                            HomeScreen(
                                viewModel = viewModel,
                                authViewModel = authViewModel,
                                onOpenProfileEdit = { isEditingProfile = true },
                                onCreateCall = { roomId ->
                                    viewModel.createRoom(roomId)
                                },
                                onJoinCall = { roomId ->
                                    viewModel.joinRoom(roomId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val isInPipMode by viewModel.isInPipMode.collectAsState()

    if (!isInPipMode && showEndCallConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showEndCallConfirmDialog = false },
            title = { Text("End Call?") },
            text = { Text("Are you sure you want to disconnect from this call?") },
            confirmButton = {
                Button(
                    onClick = {
                        showEndCallConfirmDialog = false
                        viewModel.endCall()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseDestructive)
                ) {
                    Text("End Call", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndCallConfirmDialog = false }) {
                    Text("Stay in Call")
                }
            }
        )
    }

    if (!isInPipMode && errorMessage != null) {
        AlertDialog(
            onDismissRequest = {
                errorMessage = null
                viewModel.resetToIdle()
            },
            title = { Text("Call Error") },
            text = { Text(errorMessage ?: "An unexpected error occurred") },
            confirmButton = {
                Button(
                    onClick = {
                        errorMessage = null
                        viewModel.resetToIdle()
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }
}

