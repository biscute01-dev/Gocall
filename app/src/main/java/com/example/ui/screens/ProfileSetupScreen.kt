package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auth.UserProfile
import com.example.ui.components.UserAvatar
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldConnected
import com.example.ui.theme.EmeraldGlow
import com.example.ui.theme.GlassDarkBorder
import com.example.ui.theme.GlassDarkCard
import com.example.ui.theme.GlassDarkControls
import com.example.ui.theme.IndigoAccent
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SlateDark950
import com.example.ui.theme.SlateTextMuted
import com.example.ui.theme.SlateTextPrimary
import com.example.ui.theme.SlateTextSecondary
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.UsernameStatus

@Composable
fun ProfileSetupScreen(
    authViewModel: AuthViewModel,
    suggestedDisplayName: String,
    suggestedPhotoUrl: String?,
    onProfileCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    existingProfile: UserProfile? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var displayName by remember {
        mutableStateOf(existingProfile?.displayName ?: suggestedDisplayName.ifBlank { "" })
    }
    var username by remember {
        mutableStateOf(existingProfile?.username ?: "")
    }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    val isSavingProfile by authViewModel.isSavingProfile.collectAsState()
    val usernameStatus by authViewModel.usernameStatus.collectAsState()
    val authError by authViewModel.authError.collectAsState()

    // Photo picker launcher (PickVisualMedia or GetContent)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
        }
    }

    // Trigger initial check if username is already provided
    LaunchedEffect(username) {
        if (username.isNotBlank()) {
            authViewModel.validateAndCheckUsername(username, existingProfile?.uid)
        }
    }

    // Temporary preview profile object
    val previewProfile = remember(displayName, username, suggestedPhotoUrl, existingProfile) {
        UserProfile(
            uid = existingProfile?.uid ?: "",
            displayName = displayName,
            username = username,
            photoUrl = existingProfile?.photoUrl ?: suggestedPhotoUrl,
            avatarBase64 = existingProfile?.avatarBase64,
            localPhotoUri = existingProfile?.localPhotoUri
        )
    }

    val isUsernameValid = usernameStatus is UsernameStatus.Available ||
            (isEditMode && username.equals(existingProfile?.username, ignoreCase = true) && username.length >= 3)
    val canSubmit = displayName.trim().isNotBlank() && isUsernameValid && !isSavingProfile

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(SlateDark950, Color(0xFF0B1120), SlateDark950)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Top Action Bar with Sign out or Cancel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEditMode) "Edit Profile" else "Profile Setup",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextPrimary
            )

            IconButton(
                onClick = { authViewModel.signOut() },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(GlassDarkControls)
                    .border(1.dp, GlassDarkBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Logout,
                    contentDescription = "Sign Out",
                    tint = SlateTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Profile Picture Picker Section
            Box(
                modifier = Modifier
                    .size(130.dp),
                contentAlignment = Alignment.Center
            ) {
                UserAvatar(
                    userProfile = previewProfile,
                    localImageUri = selectedImageUri,
                    size = 120.dp,
                    borderWidth = 3.dp,
                    borderColor = CyanGlow,
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.testTag("profile_avatar_picker")
                )

                // Camera overlay FAB badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 4.dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(CyanAccent, IndigoAccent))
                        )
                        .border(2.dp, SlateDark950, CircleShape)
                        .clickable {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Pick picture from gallery",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (selectedImageUri != null) "Photo selected from Gallery" else "Tap photo to change from Gallery",
                fontSize = 13.sp,
                color = if (selectedImageUri != null) CyanGlow else SlateTextSecondary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Inputs Card Container
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = GlassDarkCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassDarkBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Display Name Field
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Display Name",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SlateTextSecondary
                        )
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            placeholder = { Text("e.g. Alex Smith", color = SlateTextMuted) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Name",
                                    tint = CyanGlow
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Next
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanGlow,
                                unfocusedBorderColor = SlateDark700,
                                focusedContainerColor = SlateDark900,
                                unfocusedContainerColor = SlateDark900,
                                focusedTextColor = SlateTextPrimary,
                                unfocusedTextColor = SlateTextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("display_name_input")
                        )
                    }

                    // Unique Username Field
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Unique Username",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SlateTextSecondary
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                val filtered = it.filter { char ->
                                    char.isLetterOrDigit() || char == '_' || char == '.'
                                }.lowercase()
                                username = filtered
                                authViewModel.validateAndCheckUsername(filtered, existingProfile?.uid)
                            },
                            placeholder = { Text("username", color = SlateTextMuted) },
                            prefix = {
                                Text(
                                    "@",
                                    color = CyanGlow,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 16.sp
                                )
                            },
                            trailingIcon = {
                                when (usernameStatus) {
                                    is UsernameStatus.Checking -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = CyanGlow
                                        )
                                    }
                                    is UsernameStatus.Available -> {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Available",
                                            tint = EmeraldConnected,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    is UsernameStatus.Taken, is UsernameStatus.Invalid -> {
                                        Icon(
                                            imageVector = Icons.Default.Error,
                                            contentDescription = "Unavailable",
                                            tint = RoseDestructive,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    else -> {}
                                }
                            },
                            supportingText = {
                                when (val status = usernameStatus) {
                                    is UsernameStatus.Available -> {
                                        Text(
                                            text = "✓ @$username is available!",
                                            color = EmeraldGlow,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    is UsernameStatus.Taken -> {
                                        Text(
                                            text = "✕ @$username is already taken by another user",
                                            color = RoseDestructive,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    is UsernameStatus.Invalid -> {
                                        Text(
                                            text = status.reason,
                                            color = AmberWarning,
                                            fontSize = 12.sp
                                        )
                                    }
                                    is UsernameStatus.Checking -> {
                                        Text(
                                            text = "Checking availability...",
                                            color = SlateTextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = "3-20 characters: letters, numbers, and underscores",
                                            color = SlateTextMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (usernameStatus is UsernameStatus.Taken) RoseDestructive else CyanGlow,
                                unfocusedBorderColor = if (usernameStatus is UsernameStatus.Taken) RoseDestructive else SlateDark700,
                                focusedContainerColor = SlateDark900,
                                unfocusedContainerColor = SlateDark900,
                                focusedTextColor = SlateTextPrimary,
                                unfocusedTextColor = SlateTextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("username_input")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Error Display
            AnimatedVisibility(
                visible = authError != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = authError ?: "",
                    color = RoseDestructive,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Save / Complete Button
            Button(
                onClick = {
                    focusManager.clearFocus()
                    authViewModel.completeProfile(
                        displayName = displayName,
                        username = username,
                        avatarUri = selectedImageUri,
                        onSuccess = {
                            Toast.makeText(context, "Profile setup complete!", Toast.LENGTH_SHORT).show()
                            onProfileCompleted()
                        }
                    )
                },
                enabled = canSubmit,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanAccent,
                    disabledContainerColor = SlateDark800
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .height(54.dp)
                    .testTag("save_profile_button")
            ) {
                if (isSavingProfile) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Saving profile...",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                } else {
                    Text(
                        text = if (isEditMode) "Save Changes" else "Complete Profile",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canSubmit) SlateDark950 else SlateTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
