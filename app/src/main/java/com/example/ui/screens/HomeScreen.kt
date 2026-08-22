package com.example.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.auth.FriendRelationshipStatus
import com.example.auth.FriendRequest
import com.example.auth.FriendUser
import com.example.auth.FriendsViewModel
import com.example.auth.UserProfile
import com.example.auth.UserSearchResult
import com.example.ui.components.ProfileDialog
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

enum class HomeTab(val title: String) {
    FRIENDS("Friends"),
    SEARCH("Find People"),
    REQUESTS("Requests"),
    ROOM_CODE("Room Code")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: CallViewModel,
    authViewModel: AuthViewModel? = null,
    friendsViewModel: FriendsViewModel? = null,
    onOpenProfileEdit: () -> Unit = {},
    onDirectCallFriend: (FriendUser) -> Unit = {},
    onCreateCall: (roomId: String) -> Unit,
    onJoinCall: (roomId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current

    var selectedTab by remember { mutableStateOf(HomeTab.FRIENDS) }

    var newRoomId by remember { mutableStateOf(viewModel.generateRandomRoomId()) }
    var joinRoomIdInput by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val userProfile by (authViewModel?.currentUserProfile?.collectAsState() ?: remember { mutableStateOf(null) })
    val recentRooms by viewModel.recentRooms.collectAsState()
    val customDatabaseUrl by viewModel.customDatabaseUrl.collectAsState()

    // Sync user profile to FriendsViewModel
    LaunchedEffect(userProfile) {
        friendsViewModel?.setUserProfile(userProfile)
    }

    val friends by (friendsViewModel?.friends?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val incomingRequests by (friendsViewModel?.incomingRequests?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val outgoingRequests by (friendsViewModel?.outgoingRequests?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val searchResults by (friendsViewModel?.searchResults?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val searchQuery by (friendsViewModel?.searchQuery?.collectAsState() ?: remember { mutableStateOf("") })
    val isSearching by (friendsViewModel?.isSearching?.collectAsState() ?: remember { mutableStateOf(false) })
    val actionSuccess by (friendsViewModel?.actionSuccess?.collectAsState() ?: remember { mutableStateOf(null) })
    val actionError by (friendsViewModel?.actionError?.collectAsState() ?: remember { mutableStateOf(null) })

    LaunchedEffect(actionSuccess) {
        actionSuccess?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            friendsViewModel?.clearFeedback()
        }
    }

    LaunchedEffect(actionError) {
        actionError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            friendsViewModel?.clearFeedback()
        }
    }

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
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Bar with User Profile & Settings
                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // User Profile Pill (Avatar + Greeting + @username)
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
                                        color = SlateTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
                                        .size(40.dp)
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
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Messenger Calls",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SlateTextPrimary
                                    )
                                    Text(
                                        text = "Direct 1-on-1 Video Calling",
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

                // Modern Navigation Tabs
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateDark900),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // 1. Friends Tab
                            TabItemButton(
                                title = "Friends",
                                icon = Icons.Default.People,
                                isSelected = selectedTab == HomeTab.FRIENDS,
                                badgeCount = if (friends.isNotEmpty()) friends.size else null,
                                badgeColor = IndigoAccent,
                                onClick = { selectedTab = HomeTab.FRIENDS },
                                modifier = Modifier.weight(1f)
                            )

                            // 2. Search Tab
                            TabItemButton(
                                title = "Find",
                                icon = Icons.Default.PersonAdd,
                                isSelected = selectedTab == HomeTab.SEARCH,
                                onClick = { selectedTab = HomeTab.SEARCH },
                                modifier = Modifier.weight(1f)
                            )

                            // 3. Requests Tab
                            TabItemButton(
                                title = "Requests",
                                icon = Icons.Default.Notifications,
                                isSelected = selectedTab == HomeTab.REQUESTS,
                                badgeCount = if (incomingRequests.isNotEmpty()) incomingRequests.size else null,
                                badgeColor = RoseDestructive,
                                onClick = { selectedTab = HomeTab.REQUESTS },
                                modifier = Modifier.weight(1f)
                            )

                            // 4. Room Code Tab
                            TabItemButton(
                                title = "Room ID",
                                icon = Icons.Default.Lock,
                                isSelected = selectedTab == HomeTab.ROOM_CODE,
                                onClick = { selectedTab = HomeTab.ROOM_CODE },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Tab Content Rendering
                when (selectedTab) {
                    HomeTab.FRIENDS -> {
                        if (friends.isEmpty()) {
                            item {
                                EmptyFriendsCard(onFindFriendsClick = { selectedTab = HomeTab.SEARCH })
                            }
                        } else {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 600.dp)
                                        .padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "MY FRIENDS (${friends.size})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CyanGlow,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        text = "Tap Call to start 1-on-1 video",
                                        fontSize = 11.sp,
                                        color = SlateTextMuted
                                    )
                                }
                            }

                            items(friends, key = { it.uid }) { friend ->
                                FriendCard(
                                    friend = friend,
                                    onCallClick = {
                                        checkAndRun {
                                            if (userProfile != null) {
                                                viewModel.startDirectCall(friend, userProfile!!)
                                                onDirectCallFriend(friend)
                                            } else {
                                                Toast.makeText(context, "Please complete your profile first", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onUnfriendClick = {
                                        friendsViewModel?.removeFriend(friend.uid, friend.displayName)
                                    }
                                )
                            }
                        }
                    }

                    HomeTab.SEARCH -> {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 600.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = SlateDark900),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        text = "Find People to Call",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SlateTextPrimary
                                    )
                                    Text(
                                        text = "Search by username (e.g. @alex) or display name to send a friend request.",
                                        fontSize = 12.sp,
                                        color = SlateTextSecondary
                                    )

                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { friendsViewModel?.onSearchQueryChanged(it) },
                                        placeholder = { Text("Search @username or name...") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = "Search",
                                                tint = CyanGlow
                                            )
                                        },
                                        trailingIcon = {
                                            if (isSearching) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                    color = CyanGlow
                                                )
                                            } else if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { friendsViewModel?.onSearchQueryChanged("") }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Clear",
                                                        tint = SlateTextMuted
                                                    )
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyanGlow,
                                            unfocusedBorderColor = SlateDark700,
                                            focusedTextColor = SlateTextPrimary,
                                            unfocusedTextColor = SlateTextPrimary,
                                            focusedContainerColor = SlateDark950,
                                            unfocusedContainerColor = SlateDark950
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("friend_search_input")
                                    )
                                }
                            }
                        }

                        if (searchQuery.isNotBlank()) {
                            if (searchResults.isEmpty() && !isSearching) {
                                item {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .widthIn(max = 600.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = GlassDarkCard)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(28.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = SlateTextMuted,
                                                modifier = Modifier.size(36.dp)
                                            )
                                            Text(
                                                text = "No users found matching \"$searchQuery\"",
                                                color = SlateTextSecondary,
                                                fontSize = 14.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(searchResults, key = { it.user.uid }) { result ->
                                    UserSearchResultCard(
                                        result = result,
                                        onAddFriend = {
                                            friendsViewModel?.sendFriendRequest(result.user)
                                        },
                                        onAcceptRequest = {
                                            val req = incomingRequests.firstOrNull { it.senderUid == result.user.uid }
                                            if (req != null) {
                                                friendsViewModel?.acceptFriendRequest(req)
                                            }
                                        },
                                        onCancelRequest = {
                                            friendsViewModel?.cancelOutgoingRequest(result.user.uid)
                                        },
                                        onCallFriend = {
                                            checkAndRun {
                                                val friend = FriendUser(
                                                    uid = result.user.uid,
                                                    displayName = result.user.displayName,
                                                    username = result.user.username,
                                                    photoUrl = result.user.photoUrl,
                                                    avatarBase64 = result.user.avatarBase64
                                                )
                                                if (userProfile != null) {
                                                    viewModel.startDirectCall(friend, userProfile!!)
                                                    onDirectCallFriend(friend)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HomeTab.REQUESTS -> {
                        // Incoming Requests Section
                        item {
                            Text(
                                text = "INCOMING REQUESTS (${incomingRequests.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanGlow,
                                letterSpacing = 1.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 600.dp)
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }

                        if (incomingRequests.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 600.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = GlassDarkCard)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No pending incoming requests",
                                            color = SlateTextMuted,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            items(incomingRequests, key = { it.senderUid }) { request ->
                                IncomingRequestCard(
                                    request = request,
                                    onAccept = { friendsViewModel?.acceptFriendRequest(request) },
                                    onReject = { friendsViewModel?.rejectFriendRequest(request) }
                                )
                            }
                        }

                        // Outgoing Requests Section
                        if (outgoingRequests.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "SENT REQUESTS (${outgoingRequests.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateTextSecondary,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 600.dp)
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }

                            items(outgoingRequests, key = { it.receiverUid }) { request ->
                                OutgoingRequestCard(
                                    request = request,
                                    onCancel = { friendsViewModel?.cancelOutgoingRequest(request.receiverUid) }
                                )
                            }
                        }
                    }

                    HomeTab.ROOM_CODE -> {
                        // Start New Room Section
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
                                            text = "Start Room by Code",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SlateTextPrimary
                                        )

                                        IconButton(
                                            onClick = { newRoomId = viewModel.generateRandomRoomId() },
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
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Button(
                                        onClick = {
                                            focusManager.clearFocus()
                                            checkAndRun { onCreateCall(newRoomId) }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
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
                                                Icon(imageVector = Icons.Default.Call, contentDescription = null, tint = Color.White)
                                                Text("Create Room", fontWeight = FontWeight.Bold, color = Color.White)
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
                                        text = "Join Room by Code",
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
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Button(
                                        onClick = {
                                            focusManager.clearFocus()
                                            if (joinRoomIdInput.isBlank()) {
                                                Toast.makeText(context, "Please enter a Room ID", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            checkAndRun { onJoinCall(joinRoomIdInput) }
                                        },
                                        enabled = joinRoomIdInput.isNotBlank(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp),
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
                                            Icon(imageVector = Icons.Default.Videocam, contentDescription = null, tint = Color.White)
                                            Text("Join Room", fontWeight = FontWeight.Bold, color = Color.White)
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

                                            TextButton(onClick = { viewModel.clearRecentRooms() }) {
                                                Text("Clear", fontSize = 12.sp, color = SlateTextMuted)
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
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
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
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = CyanGlow)
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
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel", color = SlateTextMuted)
                }
            },
            containerColor = SlateDark900,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Profile Details Dialog
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

// ------------------------------------------------------------------------------------------------
// SUBCOMPONENTS: Tabs, Friend Cards, Search Results, Request Cards
// ------------------------------------------------------------------------------------------------

@Composable
private fun TabItemButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    badgeCount: Int? = null,
    badgeColor: Color = CyanGlow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) SlateDark800 else Color.Transparent)
            .border(
                1.dp,
                if (isSelected) CyanGlow.copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            BadgedBox(
                badge = {
                    if (badgeCount != null && badgeCount > 0) {
                        Badge(containerColor = badgeColor) {
                            Text(badgeCount.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isSelected) CyanGlow else SlateTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) SlateTextPrimary else SlateTextMuted
            )
        }
    }
}

@Composable
private fun FriendCard(
    friend: FriendUser,
    onCallClick: () -> Unit,
    onUnfriendClick: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val decodedBitmap = remember(friend.avatarBase64) {
        if (!friend.avatarBase64.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(friend.avatarBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .testTag("friend_card_${friend.username}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark900),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Avatar + Online Dot + User Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(modifier = Modifier.size(52.dp)) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, if (friend.isOnline) EmeraldGlow else GlassDarkBorder, CircleShape)
                            .background(SlateDark800),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            decodedBitmap != null -> {
                                Image(
                                    bitmap = decodedBitmap.asImageBitmap(),
                                    contentDescription = "Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            !friend.photoUrl.isNullOrBlank() -> {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(friend.photoUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            else -> {
                                val initial = friend.displayName.firstOrNull()?.uppercaseChar()
                                    ?: friend.username.firstOrNull()?.uppercaseChar()
                                    ?: '?'
                                Text(
                                    text = initial.toString(),
                                    color = SlateTextPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Online green badge dot
                    if (friend.isOnline) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(EmeraldConnected)
                                .border(2.dp, SlateDark900, CircleShape)
                                .align(Alignment.BottomEnd)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = friend.displayName.ifBlank { "User" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${friend.username}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanGlow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (friend.isOnline) "Active now" else "Offline",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (friend.isOnline) EmeraldConnected else SlateTextMuted
                    )
                }
            }

            // Action: Video Call Button + Overflow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onCallClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .testTag("call_friend_button_${friend.username}")
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(listOf(IndigoAccent, CyanAccent)),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "Call",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Call",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = SlateTextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(SlateDark900)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Unfriend", color = RoseDestructive) },
                            onClick = {
                                showMenu = false
                                onUnfriendClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFriendsCard(onFindFriendsClick: () -> Unit) {
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
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(IndigoAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    tint = CyanGlow,
                    modifier = Modifier.size(32.dp)
                )
            }

            Text(
                text = "No Friends Added Yet",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextPrimary
            )

            Text(
                text = "Messenger calling connects you directly with your friends. Search for people by username and send them a friend request to start calling!",
                fontSize = 13.sp,
                color = SlateTextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Button(
                onClick = onFindFriendsClick,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, tint = Color.White)
                    Text("Find People", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun UserSearchResultCard(
    result: UserSearchResult,
    onAddFriend: () -> Unit,
    onAcceptRequest: () -> Unit,
    onCancelRequest: () -> Unit,
    onCallFriend: () -> Unit
) {
    val context = LocalContext.current
    val targetUser = result.user

    val decodedBitmap = remember(targetUser.avatarBase64) {
        if (!targetUser.avatarBase64.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(targetUser.avatarBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark900),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .border(1.dp, CyanGlow.copy(alpha = 0.5f), CircleShape)
                        .background(SlateDark800),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        decodedBitmap != null -> {
                            Image(
                                bitmap = decodedBitmap.asImageBitmap(),
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        !targetUser.photoUrl.isNullOrBlank() -> {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(targetUser.photoUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> {
                            val initial = targetUser.displayName.firstOrNull()?.uppercaseChar()
                                ?: targetUser.username.firstOrNull()?.uppercaseChar()
                                ?: '?'
                            Text(
                                text = initial.toString(),
                                color = SlateTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = targetUser.displayName.ifBlank { "User" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${targetUser.username}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanGlow
                    )
                }
            }

            when (result.relationship) {
                FriendRelationshipStatus.SELF -> {
                    // Current user
                    Text(
                        text = "You",
                        fontSize = 12.sp,
                        color = SlateTextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
                FriendRelationshipStatus.FRIENDS -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = onCallFriend,
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Videocam, contentDescription = "Call", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Call", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                FriendRelationshipStatus.REQUEST_SENT -> {
                    OutlinedButton(
                        onClick = onCancelRequest,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SlateTextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SlateDark700),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Requested (Cancel)", fontSize = 11.sp, color = SlateTextMuted)
                    }
                }
                FriendRelationshipStatus.REQUEST_RECEIVED -> {
                    Button(
                        onClick = onAcceptRequest,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldConnected),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Accept", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                FriendRelationshipStatus.NOT_FRIENDS -> {
                    Button(
                        onClick = onAddFriend,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("add_friend_button_${targetUser.username}")
                    ) {
                        Icon(imageVector = Icons.Default.PersonAdd, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Friend", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomingRequestCard(
    request: FriendRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val context = LocalContext.current

    val decodedBitmap = remember(request.senderAvatarBase64) {
        if (!request.senderAvatarBase64.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(request.senderAvatarBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .testTag("incoming_request_${request.senderUsername}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark900),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .border(1.dp, CyanGlow.copy(alpha = 0.5f), CircleShape)
                        .background(SlateDark800),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        decodedBitmap != null -> {
                            Image(
                                bitmap = decodedBitmap.asImageBitmap(),
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        !request.senderPhotoUrl.isNullOrBlank() -> {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(request.senderPhotoUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> {
                            val initial = request.senderDisplayName.firstOrNull()?.uppercaseChar()
                                ?: request.senderUsername.firstOrNull()?.uppercaseChar()
                                ?: '?'
                            Text(
                                text = initial.toString(),
                                color = SlateTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request.senderDisplayName.ifBlank { "User" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${request.senderUsername}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanGlow
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldConnected),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("accept_request_${request.senderUsername}")
                ) {
                    Text("Accept", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                OutlinedButton(
                    onClick = onReject,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseDestructive),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RoseDestructive.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Decline", fontSize = 12.sp, color = RoseDestructive)
                }
            }
        }
    }
}

@Composable
private fun OutgoingRequestCard(
    request: FriendRequest,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    val decodedBitmap = remember(request.receiverAvatarBase64) {
        if (!request.receiverAvatarBase64.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(request.receiverAvatarBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark900),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(1.dp, GlassDarkBorder, CircleShape)
                        .background(SlateDark800),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        decodedBitmap != null -> {
                            Image(
                                bitmap = decodedBitmap.asImageBitmap(),
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        !request.receiverPhotoUrl.isNullOrBlank() -> {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(request.receiverPhotoUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> {
                            val initial = request.receiverDisplayName.firstOrNull()?.uppercaseChar()
                                ?: request.receiverUsername.firstOrNull()?.uppercaseChar()
                                ?: '?'
                            Text(
                                text = initial.toString(),
                                color = SlateTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request.receiverDisplayName.ifBlank { "User" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${request.receiverUsername} • Request Pending",
                        fontSize = 11.sp,
                        color = SlateTextMuted
                    )
                }
            }

            TextButton(onClick = onCancel) {
                Text("Cancel", fontSize = 12.sp, color = RoseDestructive)
            }
        }
    }
}
