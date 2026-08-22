package com.example.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.auth.UserProfile
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.IndigoAccent
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SlateTextPrimary
import java.io.File

/**
 * High-quality Google "G" vector logo.
 */
@Composable
fun GoogleLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = w / 2f

        // Google 4-color arcs
        // Red: 0 to -140 deg
        // Yellow: 140 to 90 deg
        // Green: 90 to 45 deg
        // Blue: 45 to 0 + center bar

        val blueColor = Color(0xFF4285F4)
        val greenColor = Color(0xFF34A853)
        val yellowColor = Color(0xFFFBBC05)
        val redColor = Color(0xFFEA4335)

        // Draw outer path ring
        val stroke = radius * 0.42f
        val innerR = radius - stroke

        // Red (top)
        drawArc(
            color = redColor,
            startAngle = 190f,
            sweepAngle = 135f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(w - stroke, h - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )

        // Yellow (bottom-left)
        drawArc(
            color = yellowColor,
            startAngle = 135f,
            sweepAngle = 65f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(w - stroke, h - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )

        // Green (bottom-right)
        drawArc(
            color = greenColor,
            startAngle = 35f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(w - stroke, h - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )

        // Blue (right and middle bar)
        drawArc(
            color = blueColor,
            startAngle = -35f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(w - stroke, h - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )

        // Blue crossbar
        drawRect(
            color = blueColor,
            topLeft = Offset(center.x - radius * 0.05f, center.y - stroke / 2f),
            size = Size(radius * 1.05f, stroke)
        )
    }
}

/**
 * Standard Google Sign-In button adhering to modern Material 3 styling.
 */
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    text: String = "Sign in with Google"
) {
    Surface(
        onClick = onClick,
        enabled = !isLoading,
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("google_sign_in_button")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color(0xFF1F1F1F),
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                GoogleLogo(modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = if (isLoading) "Signing in..." else text,
                color = Color(0xFF1F1F1F),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Flexible User Avatar component supporting:
 * 1. Selected local Gallery Uri
 * 2. Base64 encoded avatar string
 * 3. Local cached file path
 * 4. Google account photo URL
 * 5. Fallback user initials / icon
 */
@Composable
fun UserAvatar(
    userProfile: UserProfile?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    localImageUri: Uri? = null,
    borderWidth: Dp = 2.dp,
    borderColor: Color = CyanGlow,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val base64Bitmap = remember(userProfile?.avatarBase64) {
        userProfile?.avatarBase64?.let { b64 ->
            try {
                val decoded = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decoded, 0, decoded.size)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    val localFileBitmap = remember(userProfile?.localPhotoUri) {
        userProfile?.localPhotoUri?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(SlateDark900)
            .border(borderWidth, borderColor, CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        when {
            localImageUri != null -> {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(localImageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Profile Picture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            base64Bitmap != null -> {
                androidx.compose.foundation.Image(
                    bitmap = base64Bitmap,
                    contentDescription = "Profile Picture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            localFileBitmap != null -> {
                androidx.compose.foundation.Image(
                    bitmap = localFileBitmap,
                    contentDescription = "Profile Picture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            !userProfile?.photoUrl.isNullOrBlank() -> {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(userProfile?.photoUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Profile Picture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                // Initial Letter or Icon Fallback
                val initial = userProfile?.displayName?.trim()?.firstOrNull()?.uppercase()
                    ?: userProfile?.username?.trim()?.firstOrNull()?.uppercase()

                if (initial != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(IndigoAccent, CyanAccent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initial.toString(),
                            color = Color.White,
                            fontSize = (size.value * 0.45f).sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "User Avatar",
                        tint = CyanGlow,
                        modifier = Modifier.size(size * 0.6f)
                    )
                }
            }
        }
    }
}
