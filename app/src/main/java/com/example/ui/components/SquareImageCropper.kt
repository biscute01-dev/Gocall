package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

// WhatsApp Theme Colors
private val WhatsAppBg = Color(0xFF0B141A)
private val WhatsAppGreen = Color(0xFF00A884)
private val WhatsAppDarkBottomBar = Color(0xFF000000)

/**
 * Pixel-perfect WhatsApp Profile Picture Square Cropper Dialog.
 * Matches WhatsApp's exact viewfinder with white corner handles, mid-edge ticks,
 * 3x3 grid overlay, dark scrim, smooth pinch-zoom/pan, 90° rotation, and bottom "Cancel / Rotate / Done" bar.
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

    // Transformation States
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotationDegrees by remember { mutableIntStateOf(0) }

    // Load source bitmap safely on IO dispatcher
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
                Log.e("WhatsAppCropper", "Failed to decode bitmap: ${e.message}", e)
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
                .background(WhatsAppBg),
            color = WhatsAppBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Main Cropper Viewport with Dark Scrim & WhatsApp Frame
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val density = LocalDensity.current
                    val containerWidthPx = constraints.maxWidth.toFloat()
                    val containerHeightPx = constraints.maxHeight.toFloat()

                    // WhatsApp crop square takes ~92% of the narrower screen dimension
                    val cropSizePx = min(containerWidthPx * 0.92f, containerHeightPx * 0.92f)
                    val cropSizeDp = with(density) { cropSizePx.toDp() }

                    val cropLeft = (containerWidthPx - cropSizePx) / 2f
                    val cropTop = (containerHeightPx - cropSizePx) / 2f
                    val cropRect = Rect(cropLeft, cropTop, cropLeft + cropSizePx, cropTop + cropSizePx)

                    if (isLoading) {
                        CircularProgressIndicator(
                            color = WhatsAppGreen,
                            modifier = Modifier.size(48.dp)
                        )
                    } else if (sourceBitmap != null) {
                        val bmp = sourceBitmap!!

                        // Gesture Container across the full available viewport
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(0.6f, 6.0f)
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // Full-width background image with scale & offset transformations
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Source Photo",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(cropSizeDp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offsetX
                                        translationY = offsetY
                                        rotationZ = rotationDegrees.toFloat()
                                    }
                            )

                            // WhatsApp Overlay: Scrim outside crop square + L-corners + Mid-edge ticks + 3x3 Grid
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val totalW = size.width
                                val totalH = size.height

                                // 1. Dark Scrim outside the square crop window
                                val scrimPath = Path().apply {
                                    fillType = PathFillType.EvenOdd
                                    // Outer full screen
                                    addRect(Rect(0f, 0f, totalW, totalH))
                                    // Inner crop hole
                                    addRect(cropRect)
                                }
                                drawPath(scrimPath, color = Color.Black.copy(alpha = 0.65f))

                                // 2. Outer Thin Crop Square Border
                                drawRect(
                                    color = Color.White.copy(alpha = 0.55f),
                                    topLeft = Offset(cropRect.left, cropRect.top),
                                    size = Size(cropRect.width, cropRect.height),
                                    style = Stroke(width = 1.dp.toPx())
                                )

                                // 3. WhatsApp 3x3 Grid (Rule of Thirds)
                                val thirdW = cropRect.width / 3f
                                val thirdH = cropRect.height / 3f
                                val gridColor = Color.White.copy(alpha = 0.45f)
                                val gridStroke = 0.8.dp.toPx()

                                // Vertical lines
                                drawLine(
                                    color = gridColor,
                                    start = Offset(cropRect.left + thirdW, cropRect.top),
                                    end = Offset(cropRect.left + thirdW, cropRect.bottom),
                                    strokeWidth = gridStroke
                                )
                                drawLine(
                                    color = gridColor,
                                    start = Offset(cropRect.left + thirdW * 2f, cropRect.top),
                                    end = Offset(cropRect.left + thirdW * 2f, cropRect.bottom),
                                    strokeWidth = gridStroke
                                )

                                // Horizontal lines
                                drawLine(
                                    color = gridColor,
                                    start = Offset(cropRect.left, cropRect.top + thirdH),
                                    end = Offset(cropRect.right, cropRect.top + thirdH),
                                    strokeWidth = gridStroke
                                )
                                drawLine(
                                    color = gridColor,
                                    start = Offset(cropRect.left, cropRect.top + thirdH * 2f),
                                    end = Offset(cropRect.right, cropRect.top + thirdH * 2f),
                                    strokeWidth = gridStroke
                                )

                                // 4. WhatsApp Thick White L-Corners
                                val cornerLength = 22.dp.toPx()
                                val cornerStroke = 3.5.dp.toPx()
                                val cornerColor = Color.White

                                // Top-Left Corner
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.left - cornerStroke / 2f, cropRect.top),
                                    end = Offset(cropRect.left + cornerLength, cropRect.top),
                                    strokeWidth = cornerStroke
                                )
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.left, cropRect.top - cornerStroke / 2f),
                                    end = Offset(cropRect.left, cropRect.top + cornerLength),
                                    strokeWidth = cornerStroke
                                )

                                // Top-Right Corner
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.right + cornerStroke / 2f, cropRect.top),
                                    end = Offset(cropRect.right - cornerLength, cropRect.top),
                                    strokeWidth = cornerStroke
                                )
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.right, cropRect.top - cornerStroke / 2f),
                                    end = Offset(cropRect.right, cropRect.top + cornerLength),
                                    strokeWidth = cornerStroke
                                )

                                // Bottom-Left Corner
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.left - cornerStroke / 2f, cropRect.bottom),
                                    end = Offset(cropRect.left + cornerLength, cropRect.bottom),
                                    strokeWidth = cornerStroke
                                )
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.left, cropRect.bottom + cornerStroke / 2f),
                                    end = Offset(cropRect.left, cropRect.bottom - cornerLength),
                                    strokeWidth = cornerStroke
                                )

                                // Bottom-Right Corner
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.right + cornerStroke / 2f, cropRect.bottom),
                                    end = Offset(cropRect.right - cornerLength, cropRect.bottom),
                                    strokeWidth = cornerStroke
                                )
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.right, cropRect.bottom + cornerStroke / 2f),
                                    end = Offset(cropRect.right, cropRect.bottom - cornerLength),
                                    strokeWidth = cornerStroke
                                )

                                // 5. WhatsApp Mid-Edge Indicator Ticks
                                val midTickLength = 16.dp.toPx()
                                val midTickStroke = 3.dp.toPx()

                                // Top Edge Center Tick
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.left + cropRect.width / 2f - midTickLength / 2f, cropRect.top),
                                    end = Offset(cropRect.left + cropRect.width / 2f + midTickLength / 2f, cropRect.top),
                                    strokeWidth = midTickStroke
                                )

                                // Bottom Edge Center Tick
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.left + cropRect.width / 2f - midTickLength / 2f, cropRect.bottom),
                                    end = Offset(cropRect.left + cropRect.width / 2f + midTickLength / 2f, cropRect.bottom),
                                    strokeWidth = midTickStroke
                                )

                                // Left Edge Center Tick
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.left, cropRect.top + cropRect.height / 2f - midTickLength / 2f),
                                    end = Offset(cropRect.left, cropRect.top + cropRect.height / 2f + midTickLength / 2f),
                                    strokeWidth = midTickStroke
                                )

                                // Right Edge Center Tick
                                drawLine(
                                    color = cornerColor,
                                    start = Offset(cropRect.right, cropRect.top + cropRect.height / 2f - midTickLength / 2f),
                                    end = Offset(cropRect.right, cropRect.top + cropRect.height / 2f + midTickLength / 2f),
                                    strokeWidth = midTickStroke
                                )
                            }
                        }
                    }
                }

                // WhatsApp Minimalist Bottom Action Bar (Cancel / Rotate / Done)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(WhatsAppDarkBottomBar)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cancel Button
                        Text(
                            text = "Cancel",
                            color = WhatsAppGreen,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = false, radius = 24.dp)
                                ) {
                                    if (!isCropping) onDismiss()
                                }
                                .padding(8.dp)
                                .testTag("crop_cancel_button")
                        )

                        // WhatsApp Rotate Button (Counter-clockwise / clockwise 90 deg)
                        IconButton(
                            onClick = {
                                rotationDegrees = (rotationDegrees + 90) % 360
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .testTag("crop_rotate_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RotateRight,
                                contentDescription = "Rotate",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Done Button
                        if (isCropping) {
                            CircularProgressIndicator(
                                color = WhatsAppGreen,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Text(
                                text = "Done",
                                color = WhatsAppGreen,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable(
                                        enabled = !isLoading && sourceBitmap != null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = false, radius = 24.dp)
                                    ) {
                                        if (sourceBitmap != null && !isCropping) {
                                            isCropping = true
                                            coroutineScope.launch {
                                                val croppedUri = cropAndSaveWhatsAppSquare(
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
                                    }
                                    .padding(8.dp)
                                    .testTag("crop_done_button")
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Computes exact WhatsApp square crop based on scale, pan offset, and 90-degree rotations.
 */
private suspend fun cropAndSaveWhatsAppSquare(
    context: Context,
    source: Bitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    rotationDegrees: Int
): Uri? = withContext(Dispatchers.IO) {
    try {
        // 1. Apply rotation if rotated
        val rotatedSource = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } else {
            source
        }

        val srcWidth = rotatedSource.width.toFloat()
        val srcHeight = rotatedSource.height.toFloat()

        // Size of the square region in native pixels
        val minDim = min(srcWidth, srcHeight)
        val cropRegionSize = (minDim / scale).coerceIn(32f, max(srcWidth, srcHeight))

        // Center calculation with pan offset translation
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

        // Scale to clean standard 512x512 square profile photo
        val targetSize = 512
        val scaledSquare = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)

        // Save to cache
        val outputDir = File(context.cacheDir, "cropped_avatars").apply { mkdirs() }
        val outputFile = File(outputDir, "whatsapp_avatar_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { out ->
            scaledSquare.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        Uri.fromFile(outputFile)
    } catch (e: Exception) {
        Log.e("WhatsAppCropper", "cropAndSaveWhatsAppSquare failed: ${e.message}", e)
        null
    }
}
