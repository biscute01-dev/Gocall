package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.GlassDarkBorder
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SlateDark950
import com.example.ui.theme.SlateTextMuted
import com.example.ui.theme.SlateTextPrimary
import com.example.ui.theme.SlateTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance Facebook / WhatsApp style Square Profile Picture Cropper.
 * Allows interactive pan, pinch-zoom, and 90-degree rotation with a square viewfinder.
 */
@Composable
fun SquareImageCropperDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onCropSuccess: (Uri) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isCropping by remember { mutableStateOf(false) }

    // Transform State
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotationDegrees by remember { mutableIntStateOf(0) }

    // Load source bitmap safely
    LaunchedEffect(sourceUri) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(sourceUri)
                val decoded = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                withContext(Dispatchers.Main) {
                    sourceBitmap = decoded
                    isLoading = false
                }
            } catch (e: Exception) {
                Log.e("SquareImageCropper", "Failed to load bitmap: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(SlateDark950),
            color = SlateDark950
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SlateDark900)
                            .border(1.dp, GlassDarkBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = SlateTextPrimary
                        )
                    }

                    Text(
                        text = "Crop Profile Picture",
                        color = SlateTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(
                        onClick = {
                            rotationDegrees = (rotationDegrees + 90) % 360
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SlateDark900)
                            .border(1.dp, GlassDarkBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RotateRight,
                            contentDescription = "Rotate",
                            tint = CyanGlow
                        )
                    }
                }

                Text(
                    text = "Drag and pinch to adjust the square frame",
                    color = SlateTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Viewfinder & Canvas Area
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val density = LocalDensity.current
                    val containerWidthPx = constraints.maxWidth.toFloat()
                    val containerHeightPx = constraints.maxHeight.toFloat()
                    val cropBoxSizePx = min(containerWidthPx * 0.88f, containerHeightPx * 0.88f)
                    val cropBoxSizeDp = with(density) { cropBoxSizePx.toDp() }

                    if (isLoading) {
                        CircularProgressIndicator(
                            color = CyanGlow,
                            modifier = Modifier.size(48.dp)
                        )
                    } else if (sourceBitmap != null) {
                        val bmp = sourceBitmap!!

                        // Interactive transform container
                        Box(
                            modifier = Modifier
                                .size(cropBoxSizeDp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(2.dp, CyanGlow, RoundedCornerShape(16.dp))
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(0.5f, 5.0f)
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // Render transformed bitmap inside square
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Cropping photo",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offsetX
                                        translationY = offsetY
                                        rotationZ = rotationDegrees.toFloat()
                                    }
                            )

                            // 3x3 Grid Overlay & Viewfinder Corners
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val gridColor = Color.White.copy(alpha = 0.35f)
                                val strokeWidth = 1.dp.toPx()

                                // Vertical grid lines
                                drawLine(
                                    color = gridColor,
                                    start = Offset(w / 3f, 0f),
                                    end = Offset(w / 3f, h),
                                    strokeWidth = strokeWidth
                                )
                                drawLine(
                                    color = gridColor,
                                    start = Offset(w * 2f / 3f, 0f),
                                    end = Offset(w * 2f / 3f, h),
                                    strokeWidth = strokeWidth
                                )

                                // Horizontal grid lines
                                drawLine(
                                    color = gridColor,
                                    start = Offset(0f, h / 3f),
                                    end = Offset(w, h / 3f),
                                    strokeWidth = strokeWidth
                                )
                                drawLine(
                                    color = gridColor,
                                    start = Offset(0f, h * 2f / 3f),
                                    end = Offset(w, h * 2f / 3f),
                                    strokeWidth = strokeWidth
                                )

                                // Circular preview guide circle (dashed/subtle)
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.2f),
                                    radius = w / 2f,
                                    center = Offset(w / 2f, h / 2f),
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        }
                    }
                }

                // Zoom Controls & Slider
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomOut,
                            contentDescription = "Zoom Out",
                            tint = SlateTextMuted,
                            modifier = Modifier.size(20.dp)
                        )

                        Slider(
                            value = scale,
                            onValueChange = { scale = it },
                            valueRange = 0.8f..4.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = CyanGlow,
                                activeTrackColor = CyanAccent,
                                inactiveTrackColor = SlateDark800
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Zoom In",
                            tint = SlateTextMuted,
                            modifier = Modifier.size(20.dp)
                        )

                        IconButton(
                            onClick = {
                                scale = 1.0f
                                offsetX = 0f
                                offsetY = 0f
                                rotationDegrees = 0
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset Crop",
                                tint = SlateTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Action Buttons (Cancel / Done)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text("Cancel", color = SlateTextSecondary, fontSize = 14.sp)
                    }

                    Button(
                        onClick = {
                            if (sourceBitmap != null && !isCropping) {
                                isCropping = true
                                coroutineScope.launch {
                                    val croppedUri = cropAndSaveBitmap(
                                        context = context,
                                        source = sourceBitmap!!,
                                        scale = scale,
                                        offsetX = offsetX,
                                        offsetY = offsetY,
                                        rotationDegrees = rotationDegrees
                                    )
                                    isCropping = false
                                    if (croppedUri != null) {
                                        onCropSuccess(croppedUri)
                                    } else {
                                        onDismiss()
                                    }
                                }
                            }
                        },
                        enabled = !isLoading && !isCropping && sourceBitmap != null,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        modifier = Modifier
                            .weight(1.5f)
                            .height(50.dp)
                            .testTag("apply_crop_button")
                    ) {
                        if (isCropping) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cropping...", color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = SlateDark950,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Set Profile Picture",
                                    color = SlateDark950,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Computes high-precision square crop based on scale, pan offset, and rotation.
 */
private suspend fun cropAndSaveBitmap(
    context: Context,
    source: Bitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    rotationDegrees: Int
): Uri? = withContext(Dispatchers.IO) {
    try {
        // 1. Rotate source if needed
        val rotatedSource = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } else {
            source
        }

        val srcWidth = rotatedSource.width.toFloat()
        val srcHeight = rotatedSource.height.toFloat()

        // Size of the square region inside the original image
        val minDim = min(srcWidth, srcHeight)
        val cropRegionSize = (minDim / scale).coerceIn(32f, max(srcWidth, srcHeight))

        // Center point calculation with pan offsets inverted
        val centerX = (srcWidth / 2f) - (offsetX / scale) * (minDim / 300f)
        val centerY = (srcHeight / 2f) - (offsetY / scale) * (minDim / 300f)

        val cropLeft = (centerX - cropRegionSize / 2f).coerceIn(0f, (srcWidth - cropRegionSize).coerceAtLeast(0f))
        val cropTop = (centerY - cropRegionSize / 2f).coerceIn(0f, (srcHeight - cropRegionSize).coerceAtLeast(0f))
        val cropWidth = min(cropRegionSize, srcWidth - cropLeft)
        val cropHeight = min(cropRegionSize, srcHeight - cropTop)
        val finalCropDim = min(cropWidth, cropHeight).toInt().coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(
            rotatedSource,
            cropLeft.toInt().coerceAtLeast(0),
            cropTop.toInt().coerceAtLeast(0),
            finalCropDim,
            finalCropDim
        )

        // Scale to standard 512x512 square profile photo
        val targetSize = 512
        val scaledSquare = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)

        // Save to cache file
        val outputDir = File(context.cacheDir, "cropped_avatars").apply { mkdirs() }
        val outputFile = File(outputDir, "avatar_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { out ->
            scaledSquare.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        Uri.fromFile(outputFile)
    } catch (e: Exception) {
        Log.e("SquareImageCropper", "cropAndSaveBitmap failed: ${e.message}", e)
        null
    }
}
