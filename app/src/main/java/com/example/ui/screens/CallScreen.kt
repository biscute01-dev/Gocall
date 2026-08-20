package com.example.ui.screens

import android.content.Context
import android.content.Intent
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.unit.IntOffset
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
import com.example.ui.theme.GlassDarkCard
import com.example.ui.theme.GlassDarkControls
import com.example.ui.theme.IndigoAccent
import com.example.ui.theme.IndigoLight
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.RoseGlow
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SlateDark950
import com.example.ui.theme.SlateTextMuted
import com.example.ui.theme.SlateTextPrimary
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.webrtc.LocalVideoView
import com.example.ui.webrtc.RemoteVideoView
import com.example.viewmodel.CallState
import com.example.viewmodel.CallViewModel
import com.example.webrtc.WebRtcLiveStats
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CallScreen(
    viewModel: CallViewModel,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val callState by viewModel.callState.collectAsState()
    val roomId by viewModel.currentRoomId.collectAsState()
    val durationSeconds by viewModel.callDurationSeconds.collectAsState()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsState()
    val isMicEnabled by viewModel.isMicEnabled.collectAsState()
    val isCameraEnabled by viewModel.isCameraEnabled.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val isSpeakerOn by viewModel.audioManager.isSpeakerOn.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val liveStats by viewModel.liveStats.collectAsState()
    val showLiveHud by viewModel.showLiveHud.collectAsState()

    var showStatsDialog by remember { mutableStateOf(false) }

    // PIP draggable offset state
    var pipOffsetX by remember { mutableStateOf(0f) }
    var pipOffsetY by remember { mutableStateOf(0f) }

    val rtcClient = viewModel.webRtcClient

    Surface(
        modifier = modifier.fillMaxSize(),
        color = SlateDark950
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Remote Video Background / Full View
            if (rtcClient != null && remoteVideoTrack != null && callState is CallState.Connected) {
                RemoteVideoView(
                    webRtcClient = rtcClient,
                    videoTrack = remoteVideoTrack,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Background Fallback View (Waiting, Reconnecting, or Audio-Only)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    SlateDark900,
                                    SlateDark950,
                                    Color(0xFF060911)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (val state = callState) {
                        is CallState.WaitingForPeer -> {
                            WaitingForPeerOverlay(
                                roomId = state.roomId,
                                onShare = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "Join my 1-on-1 video call on Room ID: ${state.roomId}")
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Room ID"))
                                },
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString(state.roomId))
                                    Toast.makeText(context, "Room ID copied", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        is CallState.JoiningCall -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(color = CyanGlow, modifier = Modifier.size(48.dp))
                                Text(
                                    text = "Connecting to room ${state.roomId}...",
                                    color = SlateTextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Negotiating WebRTC peer connection via Google STUN",
                                    color = SlateTextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        is CallState.Reconnecting -> {
                            ReconnectingOverlay(
                                state = state,
                                onForceReconnect = { viewModel.forceIceRestart() }
                            )
                        }
                        is CallState.Connected -> {
                            // Connected but remote video is off / audio only
                            AudioOnlyPeerOverlay(roomId = state.roomId)
                        }
                        else -> {
                            CircularProgressIndicator(color = IndigoLight)
                        }
                    }
                }
            }

            // 2. Reconnecting Top Banner (when in reconnecting state during call)
            if (callState is CallState.Reconnecting) {
                val reconState = callState as CallState.Reconnecting
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 60.dp, start = 16.dp, end = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.9f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
                                    text = "Reconnecting (Attempt #${reconState.attempt})...",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "ICE Restarting over Google STUN",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp
                                )
                            }
                            Button(
                                onClick = { viewModel.forceIceRestart() },
                                colors = ButtonDefaults.buttonColors(containerColor = SlateDark950),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Retry", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 3. Floating Top Bar with Room ID, Duration, and Stats Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Room ID & Duration Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GlassDarkControls)
                        .border(1.dp, GlassDarkBorder, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (callState is CallState.Connected) EmeraldConnected else AmberWarning
                            )
                    )
                    Text(
                        text = roomId ?: "",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = SlateTextPrimary
                    )
                    IconButton(
                        onClick = {
                            roomId?.let {
                                clipboardManager.setText(AnnotatedString(it))
                                Toast.makeText(context, "Room ID copied", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy room ID",
                            tint = CyanGlow,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    if (callState is CallState.Connected) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(12.dp)
                                .background(SlateDark700)
                        )
                        Text(
                            text = formatCallDuration(durationSeconds),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldGlow
                        )
                    }
                }

                // Stats Action Buttons (HUD Toggle + Diagnostics modal)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Floating Live Stats HUD Toggle
                    IconButton(
                        onClick = { viewModel.toggleLiveHud() },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (showLiveHud) CyanAccent.copy(alpha = 0.25f) else GlassDarkControls)
                            .border(1.dp, if (showLiveHud) CyanGlow else GlassDarkBorder, CircleShape)
                            .testTag("toggle_live_hud_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Toggle Live Stats HUD",
                            tint = if (showLiveHud) CyanGlow else SlateTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Detailed Diagnostics / Stats Modal Button
                    IconButton(
                        onClick = {
                            viewModel.fetchStatsNow()
                            showStatsDialog = true
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(GlassDarkControls)
                            .border(1.dp, GlassDarkBorder, CircleShape)
                            .testTag("call_stats_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueryStats,
                            contentDescription = "Show WebRTC Stats",
                            tint = CyanGlow,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 4. Live On-Screen Stats HUD (Floating Overlay)
            if (showLiveHud && callState is CallState.Connected) {
                LiveStatsHud(
                    stats = liveStats,
                    onClose = { viewModel.setLiveHud(false) },
                    onOpenDetails = {
                        viewModel.fetchStatsNow()
                        showStatsDialog = true
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(top = 65.dp, start = 16.dp)
                )
            }

            // 5. Floating Local Video PIP View
            if (rtcClient != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 70.dp, end = 16.dp)
                        .offset { IntOffset(pipOffsetX.roundToInt(), pipOffsetY.roundToInt()) }
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                pipOffsetX += dragAmount.x
                                pipOffsetY += dragAmount.y
                            }
                        }
                        .size(width = 110.dp, height = 160.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SlateDark900)
                        .border(
                            1.5.dp,
                            Brush.linearGradient(listOf(CyanGlow.copy(alpha = 0.8f), IndigoAccent.copy(alpha = 0.5f))),
                            RoundedCornerShape(16.dp)
                        )
                        .testTag("local_video_pip")
                ) {
                    if (isCameraEnabled) {
                        LocalVideoView(
                            webRtcClient = rtcClient,
                            isMirror = isFrontCamera,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(SlateDark800),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideocamOff,
                                contentDescription = "Camera Off",
                                tint = SlateTextMuted,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Mini switch camera button on PIP
                    IconButton(
                        onClick = { viewModel.switchCamera() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(GlassDarkControls)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch camera",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 6. Bottom Floating Control Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = GlassDarkControls),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mic Mute
                        CallActionButton(
                            icon = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = if (isMicEnabled) "Mute Mic" else "Unmute Mic",
                            onClick = { viewModel.toggleMicrophone() },
                            isActive = isMicEnabled,
                            size = 48.dp,
                            testTag = "toggle_mic_button"
                        )

                        // Camera Toggle
                        CallActionButton(
                            icon = if (isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = if (isCameraEnabled) "Turn off camera" else "Turn on camera",
                            onClick = { viewModel.toggleCamera() },
                            isActive = isCameraEnabled,
                            size = 48.dp,
                            testTag = "toggle_camera_button"
                        )

                        // Switch Camera (Front / Back)
                        CallActionButton(
                            icon = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera",
                            onClick = { viewModel.switchCamera() },
                            isActive = true,
                            size = 48.dp,
                            testTag = "switch_camera_button"
                        )

                        // Audio Route (Speaker / Earpiece)
                        CallActionButton(
                            icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.PhoneInTalk,
                            contentDescription = if (isSpeakerOn) "Speakerphone active" else "Earpiece active",
                            onClick = { viewModel.toggleSpeaker() },
                            isActive = isSpeakerOn,
                            size = 48.dp,
                            testTag = "toggle_speaker_button"
                        )

                        // Stats & Bitrate Button
                        CallActionButton(
                            icon = Icons.Default.Speed,
                            contentDescription = "View FPS & Bitrate Stats",
                            onClick = {
                                viewModel.fetchStatsNow()
                                showStatsDialog = true
                            },
                            isActive = showLiveHud,
                            size = 48.dp,
                            testTag = "bottom_stats_button"
                        )

                        // End Call
                        CallActionButton(
                            icon = Icons.Default.CallEnd,
                            contentDescription = "End Call",
                            onClick = {
                                viewModel.endCall()
                                onEndCall()
                            },
                            isDestructive = true,
                            size = 54.dp,
                            testTag = "end_call_button"
                        )
                    }
                }
            }
        }
    }

    // Comprehensive Diagnostics & Stats Dialog
    if (showStatsDialog) {
        AlertDialog(
            onDismissRequest = { showStatsDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Speed, contentDescription = null, tint = CyanGlow)
                    Text("Live WebRTC Stream Stats", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlateTextPrimary)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Quick HUD toggle switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateDark800)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Floating On-Screen HUD",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SlateTextPrimary
                        )
                        Switch(
                            checked = showLiveHud,
                            onCheckedChange = { viewModel.setLiveHud(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyanGlow,
                                checkedTrackColor = IndigoAccent,
                                uncheckedTrackColor = SlateDark700
                            )
                        )
                    }

                    // Section 1: Inbound Video (Remote Peer)
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateDark800),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "INCOMING VIDEO (PEER)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanGlow
                            )
                            HorizontalDivider(color = GlassDarkBorder)
                            StatRow("FPS (Frames/sec)", formatFps(liveStats.inboundFps))
                            StatRow("Bitrate", formatBitrate(liveStats.inboundBitrateKbps))
                            StatRow("Resolution", liveStats.inboundResolution)
                            StatRow("Video Codec", liveStats.inboundCodec)
                            StatRow("Jitter", String.format(Locale.US, "%.1f ms", liveStats.jitterMs))
                            StatRow("Packets Lost", "${liveStats.packetsLost}")
                            StatRow("Frames Decoded", "${liveStats.framesDecoded}")
                            StatRow("Frames Dropped", "${liveStats.framesDropped}")
                        }
                    }

                    // Section 2: Outbound Video (Self)
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateDark800),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "OUTGOING VIDEO (SELF)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = IndigoLight
                            )
                            HorizontalDivider(color = GlassDarkBorder)
                            StatRow("Send FPS", formatFps(liveStats.outboundFps))
                            StatRow("Send Bitrate", formatBitrate(liveStats.outboundBitrateKbps))
                            StatRow("Send Resolution", liveStats.outboundResolution)
                            StatRow("Send Codec", liveStats.outboundCodec)
                            StatRow("Avail Outgoing Bandwidth", formatBitrate(liveStats.availableOutgoingBitrateKbps))
                        }
                    }

                    // Section 3: Connection & Network
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateDark800),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "NETWORK & NAT TRAVERSAL",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldGlow
                            )
                            HorizontalDivider(color = GlassDarkBorder)
                            StatRow("Round Trip Time (RTT)", String.format(Locale.US, "%.1f ms", liveStats.rttMs))
                            StatRow("ICE Connection State", liveStats.iceConnectionState)
                            StatRow("Peer Connection State", liveStats.connectionState)
                            StatRow("Role", if (liveStats.isCaller) "Caller (Offer)" else "Callee (Answer)")
                            StatRow("Local ICE Candidates", "${liveStats.localCandidatesCount}")
                            StatRow("Remote ICE Candidates", "${liveStats.remoteCandidatesCount}")
                            StatRow("Reconnections Count", "${liveStats.reconnectCount}")
                            StatRow("Active STUN Server", "stun.l.google.com:19302")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showStatsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = SlateDark900,
            shape = RoundedCornerShape(22.dp)
        )
    }
}

@Composable
fun LiveStatsHud(
    stats: WebRtcLiveStats,
    onClose: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark900.copy(alpha = 0.88f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyanGlow.copy(alpha = 0.5f)),
        modifier = modifier
            .widthIn(max = 240.dp)
            .clickable { onOpenDetails() }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(CyanGlow)
                    )
                    Text(
                        text = "LIVE STATS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanGlow
                    )
                }

                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Hide HUD",
                    tint = SlateTextMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onClose() }
                )
            }

            // Inbound FPS & Bitrate
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("In FPS:", fontSize = 11.sp, color = SlateTextSecondary)
                Text(
                    formatFps(stats.inboundFps),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = EmeraldGlow
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("In Bitrate:", fontSize = 11.sp, color = SlateTextSecondary)
                Text(
                    formatBitrate(stats.inboundBitrateKbps),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = CyanGlow
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Resolution:", fontSize = 11.sp, color = SlateTextSecondary)
                Text(
                    stats.inboundResolution,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = SlateTextPrimary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Out Bitrate:", fontSize = 11.sp, color = SlateTextSecondary)
                Text(
                    formatBitrate(stats.outboundBitrateKbps),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = IndigoLight
                )
            }

            if (stats.rttMs > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("RTT Ping:", fontSize = 11.sp, color = SlateTextSecondary)
                    Text(
                        String.format(Locale.US, "%.0f ms", stats.rttMs),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (stats.rttMs < 100) EmeraldGlow else AmberWarning
                    )
                }
            }
        }
    }
}

private fun formatFps(fps: Double): String {
    return if (fps > 0) String.format(Locale.US, "%.1f fps", fps) else "-- fps"
}

private fun formatBitrate(kbps: Double): String {
    return when {
        kbps <= 0 -> "-- kbps"
        kbps >= 1000 -> String.format(Locale.US, "%.2f Mbps", kbps / 1000.0)
        else -> String.format(Locale.US, "%.0f kbps", kbps)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = SlateTextSecondary)
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = SlateTextPrimary
        )
    }
}

@Composable
private fun WaitingForPeerOverlay(
    roomId: String,
    onShare: () -> Unit,
    onCopy: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_radar")
    val radarScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar"
    )
    val radarAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(130.dp)) {
            // Radar pulse ring
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(radarScale)
                    .clip(CircleShape)
                    .background(CyanGlow.copy(alpha = radarAlpha))
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(IndigoAccent, CyanAccent))
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Waiting for peer to join...",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextPrimary
            )
            Text(
                text = "Share the room code below with the person you want to call",
                fontSize = 13.sp,
                color = SlateTextSecondary,
                textAlign = TextAlign.Center
            )
        }

        // Room Code Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SlateDark800),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder),
            modifier = Modifier.clickable { onCopy() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = roomId,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = CyanGlow
                )
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = SlateTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onShare,
                colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text("Share Code", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ReconnectingOverlay(
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
                .background(AmberWarning.copy(alpha = 0.15f))
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                tint = AmberWarning,
                modifier = Modifier.size(36.dp)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Connection Dropped",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextPrimary
            )
            Text(
                text = "Attempting ICE restart #${state.attempt} (${state.reason})",
                fontSize = 13.sp,
                color = SlateTextSecondary,
                textAlign = TextAlign.Center
            )
        }

        CircularProgressIndicator(color = AmberWarning, modifier = Modifier.size(36.dp))

        Button(
            onClick = onForceReconnect,
            colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = SlateDark950, modifier = Modifier.size(16.dp))
                Text("Force Reconnect", fontWeight = FontWeight.Bold, color = SlateDark950)
            }
        }
    }
}

@Composable
private fun AudioOnlyPeerOverlay(roomId: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_wave")
    val waveScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(waveScale)
                    .clip(CircleShape)
                    .background(EmeraldConnected.copy(alpha = 0.2f))
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(SlateDark800)
                    .border(2.dp, EmeraldGlow, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = EmeraldGlow,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Connected (Audio Active)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextPrimary
            )
            Text(
                text = "Peer camera is off or initializing video stream",
                fontSize = 12.sp,
                color = SlateTextMuted
            )
        }
    }
}
