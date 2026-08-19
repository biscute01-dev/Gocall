package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.EmeraldConnected
import com.example.ui.theme.GlassDarkBorder
import com.example.ui.theme.GlassDarkCard
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateTextPrimary
import java.util.Locale

@Composable
fun StatusPill(
    text: String,
    stateColor: Color,
    isPulsing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by if (isPulsing) {
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
    } else {
        remember { androidx.compose.runtime.mutableStateOf(1.0f) }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(stateColor.copy(alpha = 0.15f))
            .border(1.dp, stateColor.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(stateColor.copy(alpha = alpha))
        )
        Text(
            text = text,
            color = stateColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun CallActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isActive: Boolean = true,
    isDestructive: Boolean = false,
    size: Dp = 56.dp,
    testTag: String = "",
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        isDestructive -> RoseDestructive
        isActive -> SlateDark800.copy(alpha = 0.9f)
        else -> Color.White.copy(alpha = 0.9f)
    }

    val iconColor = when {
        isDestructive -> Color.White
        isActive -> SlateTextPrimary
        else -> Color(0xFF0F172A)
    }

    val borderColor = when {
        isDestructive -> RoseDestructive.copy(alpha = 0.5f)
        isActive -> GlassDarkBorder
        else -> Color.White.copy(alpha = 0.8f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(1.5.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = Color.White),
                onClick = onClick
            )
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(size * 0.48f)
        )
    }
}

fun formatCallDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        val remMinutes = minutes % 60
        String.format(Locale.US, "%02d:%02d:%02d", hours, remMinutes, remainingSeconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, remainingSeconds)
    }
}
