package com.example

import android.Manifest
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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ui.screens.BroadcastScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.OnlineDevicesScreen
import com.example.ui.screens.ViewerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.SlateDark950
import com.example.viewmodel.CallState
import com.example.viewmodel.CallViewModel
import com.example.viewmodel.SurveillanceMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val callViewModel: CallViewModel by viewModels()

    companion object {
        const val ACTION_PIP_STOP = "com.example.action.PIP_STOP"
        const val ACTION_PIP_TOGGLE_AUDIO = "com.example.action.PIP_TOGGLE_AUDIO"
        const val ACTION_PIP_TOGGLE_SPEAKER = "com.example.action.PIP_TOGGLE_SPEAKER"
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_STOP -> {
                    callViewModel.stopSession()
                }
                ACTION_PIP_TOGGLE_AUDIO -> {
                    if (callViewModel.surveillanceMode.value == SurveillanceMode.VIEW) {
                        callViewModel.toggleRemoteAudio()
                    } else {
                        callViewModel.toggleMicrophone()
                    }
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
            addAction(ACTION_PIP_STOP)
            addAction(ACTION_PIP_TOGGLE_AUDIO)
            addAction(ACTION_PIP_TOGGLE_SPEAKER)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, filter)
        }

        // Sync PiP params & keep-screen-on with active surveillance session
        lifecycleScope.launch {
            combine(
                callViewModel.surveillanceMode,
                callViewModel.isRemoteAudioMuted,
                callViewModel.audioManager.isSpeakerOn
            ) { mode, _, _ ->
                val isStreamingSession =
                    mode == SurveillanceMode.BROADCAST || mode == SurveillanceMode.VIEW
                if (isStreamingSession) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                updatePipParamsIfPossible()
            }.collect {}
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SlateDark950
                ) {
                    SurveillanceApp(
                        viewModel = callViewModel,
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
        } catch (_: Exception) {
            // Receiver already unregistered
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter PiP when leaving the app in View mode so user can keep watching feed
        if (callViewModel.surveillanceMode.value == SurveillanceMode.VIEW) {
            enterPipMode()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        callViewModel.setInPipMode(isInPictureInPictureMode)
    }

    private fun enterPipMode() {
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
            } catch (_: Exception) {
                // Ignore if PiP not ready
            }
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val isAudioMuted = callViewModel.isRemoteAudioMuted.value
        val isSpeakerOn = callViewModel.audioManager.isSpeakerOn.value

        val actions = mutableListOf<RemoteAction>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioIntent = Intent(ACTION_PIP_TOGGLE_AUDIO).setPackage(packageName)
            val audioPending = PendingIntent.getBroadcast(
                this, 101, audioIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val audioIcon = if (isAudioMuted) R.drawable.ic_pip_mic_off else R.drawable.ic_pip_mic_on
            val audioTitle = if (isAudioMuted) "Unmute" else "Mute"
            actions.add(
                RemoteAction(
                    Icon.createWithResource(this, audioIcon),
                    audioTitle,
                    audioTitle,
                    audioPending
                )
            )

            val speakerIntent = Intent(ACTION_PIP_TOGGLE_SPEAKER).setPackage(packageName)
            val speakerPending = PendingIntent.getBroadcast(
                this, 102, speakerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val speakerIcon = if (isSpeakerOn) R.drawable.ic_pip_speaker_on else R.drawable.ic_pip_speaker_off
            val speakerTitle = if (isSpeakerOn) "Speaker" else "Earpiece"
            actions.add(
                RemoteAction(
                    Icon.createWithResource(this, speakerIcon),
                    speakerTitle,
                    speakerTitle,
                    speakerPending
                )
            )

            val stopIntent = Intent(ACTION_PIP_STOP).setPackage(packageName)
            val stopPending = PendingIntent.getBroadcast(
                this, 103, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            actions.add(
                RemoteAction(
                    Icon.createWithResource(this, R.drawable.ic_pip_call_end),
                    "Stop",
                    "Stop",
                    stopPending
                )
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .setActions(actions)
        } else {
            error("PiP requires Android 8.0+")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(callViewModel.surveillanceMode.value == SurveillanceMode.VIEW)
        }

        return builder.build()
    }
}

@Composable
fun SurveillanceApp(
    viewModel: CallViewModel,
    onEnterPip: () -> Unit = {}
) {
    val context = LocalContext.current
    val surveillanceMode by viewModel.surveillanceMode.collectAsState()
    val callState by viewModel.callState.collectAsState()
    val isInPipMode by viewModel.isInPipMode.collectAsState()
    val pendingRemoteWake by viewModel.pendingRemoteBroadcastWake.collectAsState()

    var showStopConfirmDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val remoteWakePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        viewModel.clearPendingRemoteBroadcastWake()
        if (cameraGranted) {
            viewModel.startBroadcast(enableAudio = audioGranted, fromRemote = true)
        } else {
            Toast.makeText(
                context,
                "Camera permission is required for remote broadcast start",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(pendingRemoteWake) {
        if (pendingRemoteWake) {
            val permissions = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            remoteWakePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    LaunchedEffect(callState) {
        when (val state = callState) {
            is CallState.Ended -> viewModel.resetToIdle()
            is CallState.Error -> errorMessage = state.message
            else -> {}
        }
    }

    val isSubScreenActive = surveillanceMode != SurveillanceMode.NONE

    BackHandler(enabled = isSubScreenActive) {
        when {
            surveillanceMode == SurveillanceMode.DEVICE_LIST -> {
                viewModel.closeDeviceList()
            }
            viewModel.isStealthMode.value -> {
                viewModel.toggleStealthMode(false)
            }
            else -> {
                showStopConfirmDialog = true
            }
        }
    }

    AnimatedContent(
        targetState = surveillanceMode,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "surveillance_mode_navigation"
    ) { mode ->
        when (mode) {
            SurveillanceMode.NONE -> {
                HomeScreen(
                    viewModel = viewModel,
                    onStartBroadcast = { enableAudio ->
                        viewModel.startBroadcast(enableAudio = enableAudio)
                    },
                    onOpenViewDevices = {
                        viewModel.openDeviceList()
                    }
                )
            }
            SurveillanceMode.DEVICE_LIST -> {
                OnlineDevicesScreen(
                    viewModel = viewModel,
                    onSelectDevice = { device ->
                        viewModel.connectToOnlineDevice(device)
                    },
                    onBack = {
                        viewModel.closeDeviceList()
                    }
                )
            }
            SurveillanceMode.BROADCAST -> {
                BroadcastScreen(
                    viewModel = viewModel,
                    onStopBroadcast = { viewModel.stopSession() }
                )
            }
            SurveillanceMode.VIEW -> {
                ViewerScreen(
                    viewModel = viewModel,
                    onStopViewing = { viewModel.stopSession(returnToDeviceList = true) },
                    onEnterPip = onEnterPip
                )
            }
        }
    }

    if (!isInPipMode && showStopConfirmDialog) {
        val isBroadcast = surveillanceMode == SurveillanceMode.BROADCAST
        AlertDialog(
            onDismissRequest = { showStopConfirmDialog = false },
            title = { Text(if (isBroadcast) "Stop Broadcast?" else "Stop Viewing?") },
            text = {
                Text(
                    if (isBroadcast) {
                        "This will turn off the camera stream for any connected viewer."
                    } else {
                        "Disconnect from the live camera feed? Any active recording will be saved automatically."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmDialog = false
                        viewModel.stopSession(returnToDeviceList = !isBroadcast)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseDestructive)
                ) {
                    Text(if (isBroadcast) "Stop Broadcast" else "Stop Viewing", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmDialog = false }) {
                    Text("Keep Running")
                }
            }
        )
    }

    if (!isInPipMode && errorMessage != null) {
        AlertDialog(
            onDismissRequest = {
                errorMessage = null
                viewModel.stopSession()
            },
            title = { Text("Stream Notice") },
            text = { Text(errorMessage ?: "Connection interrupted") },
            confirmButton = {
                Button(
                    onClick = {
                        errorMessage = null
                        viewModel.stopSession()
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }
}
