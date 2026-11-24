package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.components.StatusPill
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldConnected
import com.example.ui.theme.EmeraldGlow
import com.example.ui.theme.GlassDarkBorder
import com.example.ui.theme.GlassDarkCard
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.RoseGlow
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SlateDark950
import com.example.ui.theme.SlateTextMuted
import com.example.ui.theme.SlateTextPrimary
import com.example.ui.theme.SlateTextSecondary
import com.example.viewmodel.CallViewModel

@Composable
fun HomeScreen(
    viewModel: CallViewModel,
    onStartBroadcast: (channelId: String, enableAudio: Boolean) -> Unit,
    onStartView: (channelId: String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val activeChannel by viewModel.activeChannelId.collectAsState()
    val recentChannels by viewModel.recentChannels.collectAsState()
    val customDbUrl by viewModel.customDatabaseUrl.collectAsState()

    var channelInput by remember(activeChannel) { mutableStateOf(activeChannel) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val broadcastPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            val targetChannel = channelInput.trim().ifBlank { CallViewModel.DEFAULT_CHANNEL_ID }
            onStartBroadcast(targetChannel, audioGranted)
        } else {
            Toast.makeText(
                context,
                "Camera permission is required to Broadcast.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun launchBroadcastOneTap() {
        val targetChannel = channelInput.trim().ifBlank { CallViewModel.DEFAULT_CHANNEL_ID }
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        if (hasCamera && hasAudio && !needsNotif) {
            onStartBroadcast(targetChannel, true)
        } else {
            val toRequest = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            broadcastPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    fun launchViewOneTap() {
        val targetChannel = channelInput.trim().ifBlank { CallViewModel.DEFAULT_CHANNEL_ID }
        onStartView(targetChannel)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateDark950)
            .drawBehind {
                // Subtle dual tactical ambient glows
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(RoseDestructive.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.18f),
                        radius = size.width * 0.7f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CyanAccent.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.88f, size.height * 0.72f),
                        radius = size.width * 0.75f
                    )
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Tactical Brand Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(CyanAccent.copy(alpha = 0.25f), SlateDark800)
                                )
                            )
                            .border(1.dp, CyanGlow.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "CamGuard Logo",
                            tint = CyanGlow,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "CamGuard",
                            color = SlateTextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = "Instant P2P Surveillance",
                            color = SlateTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        text = "NO LOGIN",
                        stateColor = EmeraldGlow,
                        isPulsing = false
                    )
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(SlateDark800.copy(alpha = 0.7f))
                            .border(1.dp, GlassDarkBorder, CircleShape)
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Signaling Settings",
                            tint = SlateTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 2. Active Camera Channel Card (Pre-filled for zero-typing 1-tap pairing)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = GlassDarkCard),
                border = BorderStroke(1.dp, GlassDarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = null,
                                tint = CyanGlow,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "CAMERA CHANNEL CODE",
                                color = SlateTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Text(
                            text = "Use same code on both devices",
                            color = SlateTextMuted,
                            fontSize = 11.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = channelInput,
                            onValueChange = { next ->
                                channelInput = next
                                viewModel.setActiveChannelId(next)
                            },
                            singleLine = true,
                            placeholder = { Text("cam-01", color = SlateTextMuted) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = SlateTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanGlow,
                                unfocusedBorderColor = SlateDark700,
                                focusedContainerColor = SlateDark900,
                                unfocusedContainerColor = SlateDark900,
                                cursorColor = CyanGlow
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("channel_code_input")
                        )

                        IconButton(
                            onClick = {
                                val code = channelInput.trim().ifBlank { CallViewModel.DEFAULT_CHANNEL_ID }
                                clipboardManager.setText(AnnotatedString(code))
                                Toast.makeText(context, "Copied '$code'", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(SlateDark800)
                                .border(1.dp, SlateDark700, RoundedCornerShape(14.dp))
                                .testTag("copy_channel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Channel Code",
                                tint = SlateTextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val newCode = viewModel.generateRandomChannelId()
                                channelInput = newCode
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(SlateDark800)
                                .border(1.dp, SlateDark700, RoundedCornerShape(14.dp))
                                .testTag("random_channel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Casino,
                                contentDescription = "Generate Private Code",
                                tint = CyanGlow,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    if (recentChannels.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            recentChannels.forEach { recent ->
                                val isSelected = recent.equals(channelInput.trim(), ignoreCase = true)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) CyanAccent.copy(alpha = 0.2f) else SlateDark900,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) CyanGlow.copy(alpha = 0.6f) else SlateDark700
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            channelInput = recent
                                            viewModel.setActiveChannelId(recent)
                                        }
                                ) {
                                    Text(
                                        text = "#$recent",
                                        color = if (isSelected) CyanGlow else SlateTextSecondary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Single-Tap Mode Selector Section
            Text(
                text = "SELECT DEVICE MODE",
                color = SlateTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isWide = maxWidth >= 520.dp
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SurveillanceModeCard(
                            title = "Broadcast",
                            badge = "CAMERA UNIT",
                            subtitle = "Stream this device's rear camera & audio live to a viewer",
                            highlights = listOf("Rear Cam Default", "Stealth Blackout", "Auto-Ready"),
                            actionLabel = "TAP TO BROADCAST",
                            accentColor = RoseDestructive,
                            accentGlow = RoseGlow,
                            icon = Icons.Default.Videocam,
                            testTag = "broadcast_mode_button",
                            onClick = { launchBroadcastOneTap() },
                            modifier = Modifier.weight(1f)
                        )
                        SurveillanceModeCard(
                            title = "View",
                            badge = "MONITOR UNIT",
                            subtitle = "Watch the live camera stream & record HD MP4 clips",
                            highlights = listOf("HD Record", "No Camera Needed", "Live PiP"),
                            actionLabel = "TAP TO VIEW LIVE",
                            accentColor = CyanAccent,
                            accentGlow = CyanGlow,
                            icon = Icons.Default.Visibility,
                            testTag = "view_mode_button",
                            onClick = { launchViewOneTap() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SurveillanceModeCard(
                            title = "Broadcast",
                            badge = "CAMERA UNIT",
                            subtitle = "Stream this device's rear camera & audio live to a viewer",
                            highlights = listOf("Rear Cam Default", "Stealth Blackout", "Auto-Ready"),
                            actionLabel = "TAP TO BROADCAST",
                            accentColor = RoseDestructive,
                            accentGlow = RoseGlow,
                            icon = Icons.Default.Videocam,
                            testTag = "broadcast_mode_button",
                            onClick = { launchBroadcastOneTap() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        SurveillanceModeCard(
                            title = "View",
                            badge = "MONITOR UNIT",
                            subtitle = "Watch the live camera stream & record HD MP4 clips",
                            highlights = listOf("HD Record", "No Camera Needed", "Live PiP"),
                            actionLabel = "TAP TO VIEW LIVE",
                            accentColor = CyanAccent,
                            accentGlow = CyanGlow,
                            icon = Icons.Default.Visibility,
                            testTag = "view_mode_button",
                            onClick = { launchViewOneTap() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 4. Compact 2-Step Quick Guide
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SlateDark900.copy(alpha = 0.7f)),
                border = BorderStroke(1.dp, SlateDark800),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = EmeraldConnected,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "Phone 1 taps Broadcast. Phone 2 taps View on the same channel code ('${channelInput.trim().ifBlank { CallViewModel.DEFAULT_CHANNEL_ID }}'). Order doesn't matter — both auto-connect.",
                        color = SlateTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showSettingsDialog) {
        SignalingSettingsDialog(
            currentUrl = customDbUrl ?: "",
            onDismiss = { showSettingsDialog = false },
            onSave = { newUrl ->
                viewModel.setCustomDatabaseUrl(newUrl)
                showSettingsDialog = false
                Toast.makeText(context, "Signaling database updated", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun SurveillanceModeCard(
    title: String,
    badge: String,
    subtitle: String,
    highlights: List<String>,
    actionLabel: String,
    accentColor: Color,
    accentGlow: Color,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark900),
        border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.45f)),
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = accentGlow),
                onClick = onClick
            )
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            SlateDark900,
                            SlateDark900
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(accentColor.copy(alpha = 0.2f))
                            .border(1.dp, accentGlow.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accentGlow,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = accentColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, accentGlow.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = badge,
                            color = accentGlow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        color = SlateTextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = subtitle,
                        color = SlateTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    highlights.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SlateDark800.copy(alpha = 0.85f)
                        ) {
                            Text(
                                text = tag,
                                color = SlateTextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(accentColor, accentGlow)
                            )
                        )
                ) {
                    Text(
                        text = actionLabel,
                        color = SlateDark950,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalingSettingsDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var urlText by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateDark900,
        title = {
            Text(
                text = "Signaling Server (Optional)",
                color = SlateTextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Leave blank to use the built-in zero-config signaling server, or enter your own Firebase Realtime Database URL.",
                    color = SlateTextSecondary,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    singleLine = true,
                    placeholder = { Text("https://your-project-default-rtdb.firebaseio.com", color = SlateTextMuted, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanGlow,
                        unfocusedBorderColor = SlateDark700,
                        focusedTextColor = SlateTextPrimary,
                        unfocusedTextColor = SlateTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(urlText) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
            ) {
                Text("Save", color = SlateDark950, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SlateTextSecondary)
            }
        }
    )
}
