package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.auth.UserProfile
import com.example.ui.components.ProfileDialog
import com.example.ui.components.StatusPill
import com.example.ui.components.UserAvatar
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldConnected
import com.example.ui.theme.EmeraldGlow
import com.example.ui.theme.GlassDarkBorder
import com.example.ui.theme.GlassDarkCard
import com.example.ui.theme.IndigoAccent
import com.example.ui.theme.IndigoLight
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SlateDark950
import com.example.ui.theme.SlateTextMuted
import com.example.ui.theme.SlateTextPrimary
import com.example.ui.theme.SlateTextSecondary
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.CallViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: CallViewModel,
    authViewModel: AuthViewModel? = null,
    onOpenProfileEdit: () -> Unit = {},
    onCreateCall: (roomId: String) -> Unit,
    onJoinCall: (roomId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current

    var newRoomId by remember { mutableStateOf(viewModel.generateRandomRoomId()) }
    var joinRoomIdInput by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val userProfile by (authViewModel?.currentUserProfile?.collectAsState() ?: remember { mutableStateOf(null) })
    val recentRooms by viewModel.recentRooms.collectAsState()
    val customDatabaseUrl by viewModel.customDatabaseUrl.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (cameraGranted && audioGranted) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Toast.makeText(
                context,
                "Camera and Microphone permissions are required for video calls",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun checkAndRun(action: () -> Unit) {
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasCamera && hasAudio) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = SlateDark950
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SlateDark900,
                            SlateDark950,
                            Color(0xFF070A12)
                        )
                    )
                )
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header Bar with User Profile & Settings
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // User Profile Pill (Avatar + Greeting + @username) or App Logo
                        if (userProfile != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(32.dp))
                                    .background(GlassDarkCard)
                                    .border(1.dp, GlassDarkBorder, RoundedCornerShape(32.dp))
                                    .clickable { showProfileDialog = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .testTag("user_profile_header_pill")
                            ) {
                                UserAvatar(
                                    userProfile = userProfile,
                                    size = 38.dp,
                                    borderWidth = 1.5.dp,
                                    borderColor = CyanGlow
                                )
                                Column {
                                    Text(
                                        text = userProfile?.displayName?.ifBlank { "User" } ?: "User",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SlateTextPrimary
                                    )
                                    Text(
                                        text = "@${userProfile?.username ?: ""}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = CyanGlow
                                    )
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(IndigoAccent, CyanAccent)
                                            )
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "1-on-1 Video Call",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SlateTextPrimary
                                    )
                                    Text(
                                        text = "WebRTC • Google STUN",
                                        fontSize = 12.sp,
                                        color = CyanGlow
                                    )
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { showSettingsDialog = true },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(SlateDark800.copy(alpha = 0.6f))
                                    .border(1.dp, GlassDarkBorder, CircleShape)
                                    .testTag("settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = SlateTextSecondary
                                )
                            }
                        }
                    }
                }

                // Status Banner
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = GlassDarkCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(EmeraldConnected.copy(alpha = 0.15f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LockOpen,
                                        contentDescription = null,
                                        tint = EmeraldGlow,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "No Login Required",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = SlateTextPrimary
                                    )
                                    Text(
                                        text = "Share Room ID to start peer-to-peer call",
                                        fontSize = 12.sp,
                                        color = SlateTextMuted
                                    )
                                }
                            }

                            StatusPill(text = "STUN Online", stateColor = EmeraldGlow, isPulsing = true)
                        }
                    }
                }

                // Create Call Section
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateDark900),
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            Brush.linearGradient(listOf(CyanGlow.copy(alpha = 0.6f), IndigoAccent.copy(alpha = 0.3f)))
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Start a New Call",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateTextPrimary
                                )

                                IconButton(
                                    onClick = {
                                        newRoomId = viewModel.generateRandomRoomId()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Generate new ID",
                                        tint = CyanGlow
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = newRoomId,
                                onValueChange = { newRoomId = it.trim().lowercase() },
                                label = { Text("Call Room ID") },
                                singleLine = true,
                                trailingIcon = {
                                    Row {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(newRoomId))
                                                Toast.makeText(context, "Room ID copied: $newRoomId", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy Room ID",
                                                tint = SlateTextSecondary
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val sendIntent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, "Join my 1-on-1 video call on Room ID: $newRoomId")
                                                    type = "text/plain"
                                                }
                                                val shareIntent = Intent.createChooser(sendIntent, "Share Call Room ID")
                                                context.startActivity(shareIntent)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Share Room ID",
                                                tint = CyanGlow
                                            )
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanGlow,
                                    unfocusedBorderColor = SlateDark700,
                                    focusedLabelColor = CyanGlow,
                                    unfocusedLabelColor = SlateTextSecondary,
                                    focusedTextColor = SlateTextPrimary,
                                    unfocusedTextColor = SlateTextPrimary,
                                    focusedContainerColor = SlateDark950,
                                    unfocusedContainerColor = SlateDark950
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("create_room_id_input")
                            )

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    checkAndRun {
                                        onCreateCall(newRoomId)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("start_call_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.horizontalGradient(listOf(IndigoAccent, CyanAccent))
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Call,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                        Text(
                                            text = "Create & Start Call",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Join Call Section
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateDark900),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Join an Existing Call",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = SlateTextPrimary
                            )

                            OutlinedTextField(
                                value = joinRoomIdInput,
                                onValueChange = { joinRoomIdInput = it.trim().lowercase() },
                                label = { Text("Enter Peer Room ID") },
                                placeholder = { Text("e.g. call-4921") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    focusManager.clearFocus()
                                    if (joinRoomIdInput.isNotBlank()) {
                                        checkAndRun { onJoinCall(joinRoomIdInput) }
                                    }
                                }),
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = clipboard.primaryClip
                                            if (clip != null && clip.itemCount > 0) {
                                                val pasted = clip.getItemAt(0).text?.toString() ?: ""
                                                val clean = pasted.replace(".*Room ID:\\s*".toRegex(), "").trim().lowercase()
                                                joinRoomIdInput = clean
                                                Toast.makeText(context, "Pasted: $clean", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentPaste,
                                            contentDescription = "Paste from clipboard",
                                            tint = IndigoLight
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = IndigoLight,
                                    unfocusedBorderColor = SlateDark700,
                                    focusedLabelColor = IndigoLight,
                                    unfocusedLabelColor = SlateTextSecondary,
                                    focusedTextColor = SlateTextPrimary,
                                    unfocusedTextColor = SlateTextPrimary,
                                    focusedContainerColor = SlateDark950,
                                    unfocusedContainerColor = SlateDark950
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("join_room_id_input")
                            )

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (joinRoomIdInput.isBlank()) {
                                        Toast.makeText(context, "Please enter a Room ID", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    checkAndRun {
                                        onJoinCall(joinRoomIdInput)
                                    }
                                },
                                enabled = joinRoomIdInput.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("join_call_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = IndigoAccent,
                                    disabledContainerColor = SlateDark800
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                    Text(
                                        text = "Join Call",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // Recent Rooms
                if (recentRooms.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 600.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = GlassDarkCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = CyanGlow,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Recent Rooms",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = SlateTextPrimary
                                        )
                                    }

                                    TextButton(
                                        onClick = { viewModel.clearRecentRooms() }
                                    ) {
                                        Text(
                                            text = "Clear",
                                            fontSize = 12.sp,
                                            color = SlateTextMuted
                                        )
                                    }
                                }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    recentRooms.forEach { room ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(SlateDark800)
                                                .border(1.dp, GlassDarkBorder, RoundedCornerShape(10.dp))
                                                .clickable {
                                                    joinRoomIdInput = room
                                                    checkAndRun { onJoinCall(room) }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                                .testTag("recent_room_$room")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Videocam,
                                                contentDescription = null,
                                                tint = CyanGlow,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = room,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = SlateTextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    // Settings Dialog
    if (showSettingsDialog) {
        var tempUrl by remember { mutableStateOf(customDatabaseUrl ?: "") }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = CyanGlow
                    )
                    Text("Signaling & Network Settings", fontSize = 18.sp, color = SlateTextPrimary)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Firebase Realtime Database URL (Optional)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SlateTextPrimary
                    )
                    Text(
                        text = "Leave empty to use default pre-configured Realtime Database signaling endpoint.",
                        fontSize = 12.sp,
                        color = SlateTextMuted
                    )

                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
                        placeholder = { Text("https://<project>-default-rtdb.firebaseio.com") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanGlow,
                            unfocusedBorderColor = SlateDark700,
                            focusedTextColor = SlateTextPrimary,
                            unfocusedTextColor = SlateTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateDark950),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Active STUN Servers:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyanGlow)
                            Text("• stun:stun.l.google.com:19302", fontSize = 11.sp, color = SlateTextSecondary)
                            Text("• stun:stun1.l.google.com:19302", fontSize = 11.sp, color = SlateTextSecondary)
                            Text("• stun:stun2.l.google.com:19302", fontSize = 11.sp, color = SlateTextSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setCustomDatabaseUrl(tempUrl)
                        showSettingsDialog = false
                        Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSettingsDialog = false }
                ) {
                    Text("Cancel", color = SlateTextMuted)
                }
            },
            containerColor = SlateDark900,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Profile Details & Management Dialog
    if (showProfileDialog && userProfile != null) {
        ProfileDialog(
            userProfile = userProfile!!,
            onDismiss = { showProfileDialog = false },
            onEditProfile = {
                showProfileDialog = false
                onOpenProfileEdit()
            },
            onSignOut = {
                showProfileDialog = false
                authViewModel?.signOut()
            }
        )
    }
}
