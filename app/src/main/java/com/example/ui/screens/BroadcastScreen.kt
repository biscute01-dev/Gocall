package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.CallActionButton
import com.example.ui.components.StatusPill
import com.example.ui.components.formatCallDuration
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldConnected
import com.example.ui.theme.EmeraldGlow
import com.example.ui.theme.GlassDarkBorder
import com.example.ui.theme.GlassDarkControls
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SlateDark950
import com.example.ui.theme.SlateTextMuted
import com.example.ui.theme.SlateTextPrimary
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.webrtc.LocalVideoView
import com.example.viewmodel.CallState
import com.example.viewmodel.CallViewModel
import com.example.webrtc.WebRtcLiveStats
import java.util.Locale

@Composable
fun BroadcastScreen(
    viewModel: CallViewModel,
    onStopBroadcast: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val callState by viewModel.callState.collectAsState()
    val channelId by viewModel.currentRoomId.collectAsState()
    val durationSeconds by viewModel.callDurationSeconds.collectAsState()
    val isCameraEnabled by viewModel.isCameraEnabled.collectAsState()
    val isMicEnabled by viewModel.isMicEnabled.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val isStealthMode by viewModel.isStealthMode.collectAsState()
    val showLiveHud by viewModel.showLiveHud.collectAsState()
    val liveStats by viewModel.liveStats.collectAsState()

    val webRtcClient = viewModel.webRtcClient
    val isViewerConnected = callState is CallState.Connected

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Full-bleed Live Camera Preview
        if (webRtcClient != null && isCameraEnabled) {
            LocalVideoView(
                webRtcClient = webRtcClient,
                isMirror = isFrontCamera,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SlateDark950),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        text = "Camera Feed Paused",
                        color = SlateTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tap the camera button below to resume streaming",
                        color = SlateTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Top and bottom subtle scrims for crisp readability
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                    )
                )
        )

        // 2. Top Tactical Status Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val (statusLabel, statusColor, pulse) = when (callState) {
                    is CallState.Connected -> Triple("LIVE • VIEWER ON", EmeraldGlow, true)
                    is CallState.Reconnecting -> Triple("RECONNECTING", RoseDestructive, true)
                    else -> Triple("ON AIR • STANDBY", AmberWarning, true)
                }
                StatusPill(
                    text = statusLabel,
                    stateColor = statusColor,
                    isPulsing = pulse
                )

                // Channel code chip (tap to copy)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = GlassDarkControls,
                    border = BorderStroke(1.dp, GlassDarkBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val code = channelId ?: CallViewModel.DEFAULT_CHANNEL_ID
                            clipboardManager.setText(AnnotatedString(code))
                            Toast.makeText(context, "Channel '$code' copied", Toast.LENGTH_SHORT).show()
                        }
                        .testTag("broadcast_channel_chip")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "CH: ${channelId ?: "--"}",
                            color = CyanGlow,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Channel",
                            tint = SlateTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "• ${formatCallDuration(durationSeconds)}",
                            color = SlateTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
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
                    .testTag("broadcast_stats_toggle")
            ) {
                Icon(
                    imageVector = Icons.Default.QueryStats,
                    contentDescription = "Toggle Outbound Stats",
                    tint = if (showLiveHud) CyanGlow else SlateTextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 3. Optional Outbound Stats HUD
        AnimatedVisibility(
            visible = showLiveHud && !isStealthMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 74.dp, end = 16.dp)
        ) {
            BroadcastStatsCard(stats = liveStats, isViewerConnected = isViewerConnected)
        }

        // 4. Subtle Standby Hint when no viewer is connected yet
        if (!isViewerConnected && !isStealthMode) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = GlassDarkControls,
                border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.35f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 114.dp, start = 20.dp, end = 20.dp)
            ) {
                Text(
                    text = "Ready • Open CamGuard on another device and tap View on '${channelId ?: ""}'",
                    color = SlateTextPrimary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
        }

        // 5. Bottom Control Dock
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
                modifier = Modifier.widthIn(max = 440.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallActionButton(
                        icon = Icons.Default.Cameraswitch,
                        contentDescription = if (isFrontCamera) "Switch to Rear Camera" else "Switch to Front Camera",
                        onClick = { viewModel.switchCamera() },
                        isActive = true,
                        size = 52.dp,
                        testTag = "broadcast_flip_camera"
                    )

                    CallActionButton(
                        icon = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = if (isMicEnabled) "Mute Audio Broadcast" else "Unmute Audio Broadcast",
                        onClick = { viewModel.toggleMicrophone() },
                        isActive = isMicEnabled,
                        size = 52.dp,
                        testTag = "broadcast_mic_toggle"
                    )

                    CallActionButton(
                        icon = if (isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        contentDescription = if (isCameraEnabled) "Pause Camera" else "Resume Camera",
                        onClick = { viewModel.toggleCamera() },
                        isActive = isCameraEnabled,
                        size = 52.dp,
                        testTag = "broadcast_camera_toggle"
                    )

                    CallActionButton(
                        icon = Icons.Default.DarkMode,
                        contentDescription = "Stealth Blackout Screen",
                        onClick = { viewModel.toggleStealthMode(true) },
                        isActive = true,
                        size = 52.dp,
                        testTag = "broadcast_stealth_button"
                    )

                    CallActionButton(
                        icon = Icons.Default.Stop,
                        contentDescription = "Stop Broadcast",
                        onClick = onStopBroadcast,
                        isDestructive = true,
                        size = 58.dp,
                        testTag = "broadcast_stop_button"
                    )
                }
            }
        }

        // 6. Stealth / OLED Blackout Overlay (saves battery & dims camera device while staying live)
        AnimatedVisibility(
            visible = isStealthMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { viewModel.toggleStealthMode(false) }
                        )
                    }
                    .testTag("stealth_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isViewerConnected) EmeraldConnected.copy(alpha = 0.55f) else RoseDestructive.copy(alpha = 0.45f))
                    )
                    Text(
                        text = if (isViewerConnected) "LIVE • STEALTH MODE" else "ON AIR • STEALTH MODE",
                        color = SlateTextMuted.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "Double-tap anywhere to wake screen",
                        color = SlateTextMuted.copy(alpha = 0.4f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun BroadcastStatsCard(
    stats: WebRtcLiveStats,
    isViewerConnected: Boolean
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark900.copy(alpha = 0.88f)),
        border = BorderStroke(1.dp, GlassDarkBorder),
        modifier = Modifier.widthIn(min = 190.dp, max = 230.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "OUTBOUND TELEMETRY",
                color = CyanGlow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            StatLine("Viewer", if (isViewerConnected) "Connected" else "Standby")
            StatLine("Video", stats.outboundResolution)
            StatLine("FPS", String.format(Locale.US, "%.0f fps", stats.outboundFps))
            StatLine("Bitrate", String.format(Locale.US, "%.0f kbps", stats.outboundBitrateKbps))
            StatLine("RTT", String.format(Locale.US, "%.0f ms", stats.rttMs))
            StatLine("ICE", stats.iceConnectionState)
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = SlateTextSecondary, fontSize = 11.sp)
        Text(
            text = value,
            color = SlateTextPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}
