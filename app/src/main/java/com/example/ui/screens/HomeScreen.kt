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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    onStartBroadcast: (enableAudio: Boolean) -> Unit,
    onOpenViewDevices: () -> Unit
) {
    val context = LocalContext.current

    val deviceName by viewModel.deviceName.collectAsState()
    val onlineDevices by viewModel.onlineDevices.collectAsState()
    val customDbUrl by viewModel.customDatabaseUrl.collectAsState()

    var showRenameDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val broadcastPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        hasCameraPerm = cameraGranted
        if (cameraGranted) {
            onStartBroadcast(audioGranted)
        } else {
            Toast.makeText(context, "Camera permission is required to Broadcast.", Toast.LENGTH_LONG).show()
        }
    }

    val armPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasCameraPerm = cameraGranted
        if (cameraGranted) {
            Toast.makeText(context, "Remote camera start enabled for this device", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestPermissionsList(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    fun launchBroadcastOneTap() {
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        if (hasCamera && hasAudio && !needsNotif) {
            onStartBroadcast(true)
        } else {
            broadcastPermissionLauncher.launch(requestPermissionsList())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateDark950)
            .drawBehind {
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
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
                        text = "ONLINE",
                        stateColor = EmeraldGlow,
                        isPulsing = true
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

            // 2. This Device Name Identity Card (Replaces Room Codes completely)
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CyanAccent.copy(alpha = 0.16f))
                                .border(1.dp, CyanGlow.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Smartphone,
                                contentDescription = "This Device",
                                tint = CyanGlow,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "THIS DEVICE NAME",
                                color = SlateTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = deviceName,
                                color = SlateTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { showRenameDialog = true },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SlateDark800)
                                .border(1.dp, SlateDark700, RoundedCornerShape(12.dp))
                                .testTag("rename_device_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Rename Device",
                                tint = CyanGlow,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Remote Auto-Start Readiness Row
                    if (hasCameraPerm) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldGlow,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Ready for instant remote broadcast when tapped from another phone",
                                color = EmeraldGlow,
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { armPermissionLauncher.launch(requestPermissionsList()) },
                            border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("enable_remote_camera_button")
                        ) {
                            Text(
                                text = "Allow Camera & Mic for Auto Remote Start",
                                color = AmberWarning,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 3. Single-Tap Mode Selector
            Text(
                text = "SELECT MODE",
                color = SlateTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isWide = maxWidth >= 520.dp
                val viewBadge = if (onlineDevices.isNotEmpty()) "${onlineDevices.size} ONLINE" else "MONITOR UNIT"
                val viewAction = if (onlineDevices.isNotEmpty()) {
                    "VIEW ONLINE DEVICES (${onlineDevices.size})"
                } else {
                    "OPEN ONLINE DEVICES"
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SurveillanceModeCard(
                            title = "Broadcast",
                            badge = "CAMERA UNIT",
                            subtitle = "Start streaming this device's camera immediately with stealth blackout",
                            highlights = listOf("Rear Cam Default", "Stealth Blackout", "Remote Controlled"),
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
                            badge = viewBadge,
                            subtitle = "Pick any online device to auto-start its camera, watch live & record HD",
                            highlights = listOf("Auto-Start Cam", "Full Remote Control", "HD Record"),
                            actionLabel = viewAction,
                            accentColor = CyanAccent,
                            accentGlow = CyanGlow,
                            icon = Icons.Default.Visibility,
                            testTag = "view_mode_button",
                            onClick = onOpenViewDevices,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SurveillanceModeCard(
                            title = "View",
                            badge = viewBadge,
                            subtitle = "Pick any online device to auto-start its camera, watch live & record HD",
                            highlights = listOf("Auto-Start Cam", "Full Remote Control", "HD Record"),
                            actionLabel = viewAction,
                            accentColor = CyanAccent,
                            accentGlow = CyanGlow,
                            icon = Icons.Default.Visibility,
                            testTag = "view_mode_button",
                            onClick = onOpenViewDevices,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SurveillanceModeCard(
                            title = "Broadcast",
                            badge = "CAMERA UNIT",
                            subtitle = "Start streaming this device's camera immediately with stealth blackout",
                            highlights = listOf("Rear Cam Default", "Stealth Blackout", "Remote Controlled"),
                            actionLabel = "TAP TO BROADCAST",
                            accentColor = RoseDestructive,
                            accentGlow = RoseGlow,
                            icon = Icons.Default.Videocam,
                            testTag = "broadcast_mode_button",
                            onClick = { launchBroadcastOneTap() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 4. Compact Tip Card
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
                        text = "No room codes needed. Open CamGuard on any phone, then tap View here and pick its name — the camera starts remotely.",
                        color = SlateTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showRenameDialog) {
        RenameDeviceDialog(
            currentName = deviceName,
            onDismiss = { showRenameDialog = false },
            onSave = { newName ->
                viewModel.setDeviceName(newName)
                showRenameDialog = false
                Toast.makeText(context, "Device renamed to '${newName.trim()}'", Toast.LENGTH_SHORT).show()
            }
        )
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
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(accentColor.copy(alpha = 0.2f))
                            .border(1.dp, accentGlow.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accentGlow,
                            modifier = Modifier.size(28.dp)
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
private fun RenameDeviceDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var nameText by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateDark900,
        title = {
            Text(
                text = "Rename This Device",
                color = SlateTextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "This name is shown in the Online Devices list on other phones (e.g. Front Door, Living Room, Baby Cam).",
                    color = SlateTextSecondary,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it.take(26) },
                    singleLine = true,
                    placeholder = { Text("Living Room Cam", color = SlateTextMuted) },
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
                onClick = { onSave(nameText) },
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
