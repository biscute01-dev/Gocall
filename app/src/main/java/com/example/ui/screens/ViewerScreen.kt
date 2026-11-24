package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.CallActionButton
import com.example.ui.components.StatusPill
import com.example.ui.components.formatCallDuration
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldConnected
import com.example.ui.theme.EmeraldGlow
import com.example.ui.theme.GlassDarkBorder
import com.example.ui.theme.GlassDarkControls
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SlateDark950
import com.example.ui.theme.SlateTextPrimary
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.webrtc.RemoteVideoView
import com.example.viewmodel.CallState
import com.example.viewmodel.CallViewModel
import com.example.webrtc.WebRtcLiveStats
import com.example.webrtc.record.RecordingStatus
import java.util.Locale

@Composable
fun ViewerScreen(
    viewModel: CallViewModel,
    onStopViewing: () -> Unit,
    onEnterPip: () -> Unit = {}
) {
    val context = LocalContext.current

    val callState by viewModel.callState.collectAsState()
    val channelId by viewModel.currentRoomId.collectAsState()
    val durationSeconds by viewModel.callDurationSeconds.collectAsState()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsState()
    val isRemoteCameraEnabled by viewModel.isRemoteCameraEnabled.collectAsState()
    val isRemoteAudioMuted by viewModel.isRemoteAudioMuted.collectAsState()
    val isSpeakerOn by viewModel.audioManager.isSpeakerOn.collectAsState()
    val recordingStatus by viewModel.recordingStatus.collectAsState()
    val showLiveHud by viewModel.showLiveHud.collectAsState()
    val liveStats by viewModel.liveStats.collectAsState()
    val isInPipMode by viewModel.isInPipMode.collectAsState()

    val webRtcClient = viewModel.webRtcClient
    val isRecording = recordingStatus is RecordingStatus.Recording
    val isProcessingRecording = recordingStatus is RecordingStatus.Processing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateDark950)
    ) {
        // 1. Full-Bleed Live Surveillance Video Feed
        if (webRtcClient != null && remoteVideoTrack != null && isRemoteCameraEnabled) {
            RemoteVideoView(
                webRtcClient = webRtcClient,
                videoTrack = remoteVideoTrack,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(SlateDark900, SlateDark950)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    callState is CallState.Reconnecting -> {
                        ViewerReconnectingOverlay(
                            state = callState as CallState.Reconnecting,
                            onForceReconnect = { viewModel.forceReconnect() }
                        )
                    }
                    remoteVideoTrack != null && !isRemoteCameraEnabled -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(SlateDark800)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VideocamOff,
                                    contentDescription = "Camera Paused",
                                    tint = SlateTextSecondary,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Text(
                                text = "Camera Paused",
                                color = SlateTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "The broadcaster temporarily paused video on '${channelId ?: ""}'",
                                color = SlateTextSecondary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        WaitingForCameraOverlay(
                            channelId = channelId ?: CallViewModel.DEFAULT_CHANNEL_ID,
                            onRetry = { viewModel.forceReconnect() }
                        )
                    }
                }
            }
        }

        if (!isInPipMode) {
            // Subtle top & bottom gradient scrims
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
            )

            // 2. Full Reconnect Top Banner (Resume stays silent; only fires on Full Reconnect escalation)
            if (callState is CallState.Reconnecting && remoteVideoTrack != null) {
                val reconState = callState as CallState.Reconnecting
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 68.dp, start = 16.dp, end = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = RoseDestructive.copy(alpha = 0.95f)),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Full Reconnect (#${reconState.attempt}/${reconState.maxAttempts})",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Rebuilding camera stream...",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp
                                )
                            }
                            Button(
                                onClick = { viewModel.forceReconnect() },
                                colors = ButtonDefaults.buttonColors(containerColor = SlateDark950),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Retry", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 3. Top Tactical Status Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val (label, color, pulse) = when {
                            callState is CallState.Connected && remoteVideoTrack != null ->
                                Triple("LIVE", EmeraldGlow, true)
                            callState is CallState.Reconnecting ->
                                Triple("RECONNECTING", RoseDestructive, true)
                            else ->
                                Triple("WAITING", CyanGlow, true)
                        }
                        StatusPill(text = label, stateColor = color, isPulsing = pulse)

                        if (recordingStatus is RecordingStatus.Recording) {
                            val rec = recordingStatus as RecordingStatus.Recording
                            StatusPill(
                                text = "REC ${formatCallDuration(rec.durationSeconds)}",
                                stateColor = RoseDestructive,
                                isPulsing = true
                            )
                        } else if (isProcessingRecording) {
                            StatusPill(
                                text = "SAVING MP4...",
                                stateColor = AmberWarning,
                                isPulsing = true
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = GlassDarkControls,
                        border = BorderStroke(1.dp, GlassDarkBorder)
                    ) {
                        Text(
                            text = "CH: ${channelId ?: "--"} • ${formatCallDuration(durationSeconds)}",
                            color = SlateTextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onEnterPip,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(GlassDarkControls)
                            .border(1.dp, GlassDarkBorder, CircleShape)
                            .testTag("viewer_pip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = "Picture in Picture",
                            tint = SlateTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleLiveHud() },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (showLiveHud) CyanGlow.copy(alpha = 0.25f) else GlassDarkControls)
                            .border(
                                1.dp,
                                if (showLiveHud) CyanGlow else GlassDarkBorder,
                                CircleShape
                            )
                            .testTag("viewer_stats_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueryStats,
                            contentDescription = "Toggle Live Stream Stats",
                            tint = if (showLiveHud) CyanGlow else SlateTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 4. Optional Inbound Live Stats HUD
            AnimatedVisibility(
                visible = showLiveHud,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 74.dp, end = 16.dp)
            ) {
                ViewerStatsCard(stats = liveStats)
            }

            // 5. Recording Saved / Error Banner above bottom dock
            when (val recState = recordingStatus) {
                is RecordingStatus.Saved -> {
                    RecordingSavedCard(
                        saved = recState,
                        onPlay = {
                            val intent = viewModel.createViewRecordingIntent(recState.file, recState.mediaStoreUri)
                            if (intent != null) {
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No video player found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onShare = {
                            val intent = viewModel.createShareRecordingIntent(recState.file, recState.mediaStoreUri)
                            if (intent != null) {
                                try {
                                    context.startActivity(android.content.Intent.createChooser(intent, "Share Surveillance Recording"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Unable to share video", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDismiss = { viewModel.dismissRecordingStatus() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 108.dp, start = 16.dp, end = 16.dp)
                    )
                }
                is RecordingStatus.Error -> {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = RoseDestructive.copy(alpha = 0.92f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 108.dp, start = 16.dp, end = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = recState.message,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.dismissRecordingStatus() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                else -> {}
            }

            // 6. Bottom Floating Monitor Dock (Record, Mute Audio, Speaker, Refresh, Stop)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = GlassDarkControls,
                    border = BorderStroke(1.dp, GlassDarkBorder),
                    modifier = Modifier.widthIn(max = 460.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Record Button (prominent surveillance recording control)
                        RecordActionButton(
                            isRecording = isRecording,
                            isProcessing = isProcessingRecording,
                            isEnabled = remoteVideoTrack != null && !isProcessingRecording,
                            onClick = {
                                if (remoteVideoTrack == null) {
                                    Toast.makeText(
                                        context,
                                        "Wait for live video stream to start recording",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    viewModel.toggleRemoteRecording()
                                }
                            }
                        )

                        CallActionButton(
                            icon = if (isRemoteAudioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (isRemoteAudioMuted) "Unmute Feed Audio" else "Mute Feed Audio",
                            onClick = { viewModel.toggleRemoteAudio() },
                            isActive = !isRemoteAudioMuted,
                            size = 52.dp,
                            testTag = "viewer_mute_audio_button"
                        )

                        CallActionButton(
                            icon = if (isSpeakerOn) Icons.Default.SpeakerPhone else Icons.Default.Hearing,
                            contentDescription = if (isSpeakerOn) "Switch to Earpiece" else "Switch to Speaker",
                            onClick = { viewModel.toggleSpeaker() },
                            isActive = isSpeakerOn,
                            size = 52.dp,
                            testTag = "viewer_speaker_button"
                        )

                        CallActionButton(
                            icon = Icons.Default.Refresh,
                            contentDescription = "Reconnect Feed",
                            onClick = { viewModel.forceReconnect() },
                            isActive = true,
                            size = 52.dp,
                            testTag = "viewer_reconnect_button"
                        )

                        CallActionButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Stop Viewing",
                            onClick = onStopViewing,
                            isDestructive = true,
                            size = 56.dp,
                            testTag = "viewer_stop_button"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordActionButton(
    isRecording: Boolean,
    isProcessing: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_scale"
    )

    val bgColor = when {
        isRecording -> RoseDestructive
        isEnabled -> SlateDark800
        else -> SlateDark900.copy(alpha = 0.6f)
    }
    val borderColor = when {
        isRecording -> Color.White
        isEnabled -> RoseDestructive.copy(alpha = 0.75f)
        else -> SlateDark700
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(58.dp)
            .scale(if (isRecording) pulseScale else 1f)
            .clip(CircleShape)
            .background(bgColor)
            .border(2.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = RoseDestructive),
                onClick = onClick
            )
            .testTag("viewer_record_button")
    ) {
        when {
            isProcessing -> {
                CircularProgressIndicator(
                    color = AmberWarning,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(24.dp)
                )
            }
            isRecording -> {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop Recording",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = "Start Recording Live Feed",
                    tint = if (isEnabled) RoseDestructive else SlateDark700,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun WaitingForCameraOverlay(
    channelId: String,
    onRetry: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val radarScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radar_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .scale(radarScale)
                    .clip(CircleShape)
                    .background(CyanAccent.copy(alpha = 0.14f))
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(SlateDark800)
                    .border(1.5.dp, CyanGlow.copy(alpha = 0.55f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = "Searching for Camera",
                    tint = CyanGlow,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Waiting for Camera...",
                color = SlateTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Listening on channel '$channelId'.\nTap Broadcast on your camera device — live feed will start automatically.",
                color = SlateTextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )
        }

        OutlinedButton(
            onClick = onRetry,
            border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = CyanGlow, modifier = Modifier.size(16.dp))
                Text("Refresh Channel", color = CyanGlow, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ViewerReconnectingOverlay(
    state: CallState.Reconnecting,
    onForceReconnect: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(RoseDestructive.copy(alpha = 0.15f))
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                tint = RoseDestructive,
                modifier = Modifier.size(36.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Reconnecting • Full Reconnect",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextPrimary
            )
            Text(
                text = "Rebuilding camera stream #${state.attempt}/${state.maxAttempts} (${state.reason})",
                fontSize = 13.sp,
                color = SlateTextSecondary,
                textAlign = TextAlign.Center
            )
        }

        CircularProgressIndicator(color = RoseDestructive, modifier = Modifier.size(34.dp))

        Button(
            onClick = onForceReconnect,
            colors = ButtonDefaults.buttonColors(containerColor = RoseDestructive),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Force Reconnect", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun RecordingSavedCard(
    saved: RecordingStatus.Saved,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizeMb = saved.fileSizeBytes / (1024.0 * 1024.0)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark900.copy(alpha = 0.95f)),
        border = BorderStroke(1.dp, EmeraldConnected.copy(alpha = 0.55f)),
        modifier = modifier.widthIn(max = 420.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Recording Saved",
                    tint = EmeraldGlow,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clip Saved to Gallery",
                        color = SlateTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "${formatCallDuration(saved.durationSeconds)} • ${String.format(Locale.US, "%.1f MB", sizeMb)} • Movies/CamGuard",
                        color = SlateTextSecondary,
                        fontSize = 11.sp
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SlateTextSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldConnected),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).testTag("play_recording_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = SlateDark950, modifier = Modifier.size(18.dp))
                    Text("Play", color = SlateDark950, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onShare,
                    border = BorderStroke(1.dp, SlateDark700),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).testTag("share_recording_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = SlateTextPrimary, modifier = Modifier.size(16.dp))
                    Text(" Share", color = SlateTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ViewerStatsCard(stats: WebRtcLiveStats) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark900.copy(alpha = 0.88f)),
        border = BorderStroke(1.dp, GlassDarkBorder),
        modifier = Modifier.widthIn(min = 200.dp, max = 240.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "INBOUND STREAM STATS",
                color = CyanGlow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            ViewerStatRow("Resolution", stats.inboundResolution)
            ViewerStatRow("FPS", String.format(Locale.US, "%.0f fps", stats.inboundFps))
            ViewerStatRow("Bitrate", String.format(Locale.US, "%.0f kbps", stats.inboundBitrateKbps))
            ViewerStatRow("Codec", stats.inboundCodec)
            ViewerStatRow("RTT", String.format(Locale.US, "%.0f ms", stats.rttMs))
            ViewerStatRow("Jitter", String.format(Locale.US, "%.1f ms", stats.jitterMs))
            ViewerStatRow(
                "Packet Loss",
                "${stats.packetsLost}",
                valueColor = if (stats.packetsLost == 0L) EmeraldGlow else RoseDestructive
            )
            if (stats.reconnectCount > 0) {
                ViewerStatRow(
                    "Reconnects",
                    "${stats.reconnectCount} (${stats.reconnectStrategy})",
                    valueColor = AmberWarning
                )
            }
        }
    }
}

@Composable
private fun ViewerStatRow(
    label: String,
    value: String,
    valueColor: Color = SlateTextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = SlateTextSecondary, fontSize = 11.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}
