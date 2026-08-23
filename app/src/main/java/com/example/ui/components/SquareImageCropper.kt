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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// WhatsApp Dark Theme Colors
private val WhatsAppBg = Color(0xFF0B141A)
private val WhatsAppGreen = Color(0xFF00A884)
private val WhatsAppDarkBottomBar = Color(0xFF000000)

private enum class DragHandle {
    NONE,
    BODY,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_EDGE,
    BOTTOM_EDGE,
    LEFT_EDGE,
    RIGHT_EDGE
}

/**
 * WhatsApp Profile Picture Square Cropper Dialog.
 *
 * Feature behavior:
 * - The image stays stationary in the center of the viewport.
 * - The square cropping window can be dragged freely across the image.
 * - The 4 white L-corners and 4 edge handles can be dragged to expand or shrink the square crop area.
 * - All movements and resizes are strictly clamped inside the stationary image bounds.
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
    var rotationDegrees by remember { mutableIntStateOf(0) }

    // Cropping box geometry state hoisted to dialog scope
    var cropLeft by remember { mutableFloatStateOf(0f) }
    var cropTop by remember { mutableFloatStateOf(0f) }
    var cropSize by remember { mutableFloatStateOf(0f) }
    var activeImgRect by remember { mutableStateOf(Rect.Zero) }

    // Load source bitmap on IO dispatcher
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
                // Interactive Cropper Viewport
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val density = LocalDensity.current
                    val containerWidth = constraints.maxWidth.toFloat()
                    val containerHeight = constraints.maxHeight.toFloat()

                    if (isLoading) {
                        CircularProgressIndicator(
                            color = WhatsAppGreen,
                            modifier = Modifier.size(48.dp)
                        )
                    } else if (sourceBitmap != null) {
                        // Obtain rotated bitmap instance
                        val bmp = remember(sourceBitmap, rotationDegrees) {
                            if (rotationDegrees != 0) {
                                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                                Bitmap.createBitmap(sourceBitmap!!, 0, 0, sourceBitmap!!.width, sourceBitmap!!.height, matrix, true)
                            } else {
                                sourceBitmap!!
                            }
                        }

                        // Calculate fitted image rect inside container
                        val imgWidth = bmp.width.toFloat()
                        val imgHeight = bmp.height.toFloat()
                        val containerAspect = containerWidth / containerHeight
                        val imgAspect = imgWidth / imgHeight

                        val displayedImgWidth: Float
                        val displayedImgHeight: Float
                        if (imgAspect > containerAspect) {
                            // Width fits container, height letterboxed
                            displayedImgWidth = containerWidth
                            displayedImgHeight = containerWidth / imgAspect
                        } else {
                            // Height fits container, width pillarboxed
                            displayedImgHeight = containerHeight
                            displayedImgWidth = containerHeight * imgAspect
                        }

                        val imgLeft = (containerWidth - displayedImgWidth) / 2f
                        val imgTop = (containerHeight - displayedImgHeight) / 2f
                        val imgRect = remember(displayedImgWidth, displayedImgHeight, imgLeft, imgTop) {
                            Rect(imgLeft, imgTop, imgLeft + displayedImgWidth, imgTop + displayedImgHeight)
                        }

                        // Initialize or update crop rect on rotation/load
                        LaunchedEffect(imgRect) {
                            activeImgRect = imgRect
                            val initialSize = min(imgRect.width, imgRect.height) * 0.85f
                            cropSize = initialSize
                            cropLeft = imgRect.left + (imgRect.width - initialSize) / 2f
                            cropTop = imgRect.top + (imgRect.height - initialSize) / 2f
                        }

                        var activeHandle by remember { mutableStateOf(DragHandle.NONE) }

                        val minCropSizePx = with(density) { 50.dp.toPx() }
                        val touchRadiusPx = with(density) { 42.dp.toPx() }

                        // Canvas + Pointer Input for stationary image & draggable/resizable crop window
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(rotationDegrees, displayedImgWidth, displayedImgHeight) {
                                    detectDragGestures(
                                        onDragStart = { startOffset ->
                                            val currentRect = Rect(cropLeft, cropTop, cropLeft + cropSize, cropTop + cropSize)

                                            // 1. Check 4 Corners (Highest Priority)
                                            val distTopLeft = (startOffset - currentRect.topLeft).getDistance()
                                            val distTopRight = (startOffset - currentRect.topRight).getDistance()
                                            val distBottomLeft = (startOffset - currentRect.bottomLeft).getDistance()
                                            val distBottomRight = (startOffset - currentRect.bottomRight).getDistance()

                                            // 2. Check 4 Mid Edges
                                            val midTop = Offset(currentRect.left + currentRect.width / 2f, currentRect.top)
                                            val midBottom = Offset(currentRect.left + currentRect.width / 2f, currentRect.bottom)
                                            val midLeft = Offset(currentRect.left, currentRect.top + currentRect.height / 2f)
                                            val midRight = Offset(currentRect.right, currentRect.top + currentRect.height / 2f)

                                            val distTopEdge = (startOffset - midTop).getDistance()
                                            val distBottomEdge = (startOffset - midBottom).getDistance()
                                            val distLeftEdge = (startOffset - midLeft).getDistance()
                                            val distRightEdge = (startOffset - midRight).getDistance()

                                            activeHandle = when {
                                                distTopLeft <= touchRadiusPx -> DragHandle.TOP_LEFT
                                                distTopRight <= touchRadiusPx -> DragHandle.TOP_RIGHT
                                                distBottomLeft <= touchRadiusPx -> DragHandle.BOTTOM_LEFT
                                                distBottomRight <= touchRadiusPx -> DragHandle.BOTTOM_RIGHT
                                                distTopEdge <= touchRadiusPx -> DragHandle.TOP_EDGE
                                                distBottomEdge <= touchRadiusPx -> DragHandle.BOTTOM_EDGE
                                                distLeftEdge <= touchRadiusPx -> DragHandle.LEFT_EDGE
                                                distRightEdge <= touchRadiusPx -> DragHandle.RIGHT_EDGE
                                                currentRect.contains(startOffset) -> DragHandle.BODY
                                                else -> DragHandle.NONE
                                            }
                                        },
                                        onDragEnd = { activeHandle = DragHandle.NONE },
                                        onDragCancel = { activeHandle = DragHandle.NONE },
                                        onDrag = { change, dragAmount ->
                                            change.consume()

                                            when (activeHandle) {
                                                DragHandle.BODY -> {
                                                    // Move entire square crop box inside image bounds
                                                    val newLeft = (cropLeft + dragAmount.x).coerceIn(
                                                        imgRect.left,
                                                        imgRect.right - cropSize
                                                    )
                                                    val newTop = (cropTop + dragAmount.y).coerceIn(
                                                        imgRect.top,
                                                        imgRect.bottom - cropSize
                                                    )
                                                    cropLeft = newLeft
                                                    cropTop = newTop
                                                }

                                                DragHandle.TOP_LEFT -> {
                                                    val anchorRight = cropLeft + cropSize
                                                    val anchorBottom = cropTop + cropSize
                                                    val delta = max(-dragAmount.x, -dragAmount.y)
                                                    val maxAllowedSize = min(anchorRight - imgRect.left, anchorBottom - imgRect.top)
                                                    val newSize = (cropSize + delta).coerceIn(minCropSizePx, maxAllowedSize)
                                                    cropSize = newSize
                                                    cropLeft = anchorRight - newSize
                                                    cropTop = anchorBottom - newSize
                                                }

                                                DragHandle.TOP_RIGHT -> {
                                                    val anchorLeft = cropLeft
                                                    val anchorBottom = cropTop + cropSize
                                                    val delta = max(dragAmount.x, -dragAmount.y)
                                                    val maxAllowedSize = min(imgRect.right - anchorLeft, anchorBottom - imgRect.top)
                                                    val newSize = (cropSize + delta).coerceIn(minCropSizePx, maxAllowedSize)
                                                    cropSize = newSize
                                                    cropLeft = anchorLeft
                                                    cropTop = anchorBottom - newSize
                                                }

                                                DragHandle.BOTTOM_LEFT -> {
                                                    val anchorRight = cropLeft + cropSize
                                                    val anchorTop = cropTop
                                                    val delta = max(-dragAmount.x, dragAmount.y)
                                                    val maxAllowedSize = min(anchorRight - imgRect.left, imgRect.bottom - anchorTop)
                                                    val newSize = (cropSize + delta).coerceIn(minCropSizePx, maxAllowedSize)
                                                    cropSize = newSize
                                                    cropLeft = anchorRight - newSize
                                                    cropTop = anchorTop
                                                }

                                                DragHandle.BOTTOM_RIGHT -> {
                                                    val anchorLeft = cropLeft
                                                    val anchorTop = cropTop
                                                    val delta = max(dragAmount.x, dragAmount.y)
                                                    val maxAllowedSize = min(imgRect.right - anchorLeft, imgRect.bottom - anchorTop)
                                                    val newSize = (cropSize + delta).coerceIn(minCropSizePx, maxAllowedSize)
                                                    cropSize = newSize
                                                    cropLeft = anchorLeft
                                                    cropTop = anchorTop
                                                }

                                                DragHandle.TOP_EDGE -> {
                                                    val anchorBottom = cropTop + cropSize
                                                    val centerX = cropLeft + cropSize / 2f
                                                    val delta = -dragAmount.y
                                                    val maxAllowedFromTop = anchorBottom - imgRect.top
                                                    val maxAllowedFromWidth = min(centerX - imgRect.left, imgRect.right - centerX) * 2f
                                                    val maxAllowedSize = min(maxAllowedFromTop, maxAllowedFromWidth)
                                                    val newSize = (cropSize + delta).coerceIn(minCropSizePx, maxAllowedSize)
                                                    cropSize = newSize
                                                    cropTop = anchorBottom - newSize
                                                    cropLeft = (centerX - newSize / 2f).coerceIn(imgRect.left, imgRect.right - newSize)
                                                }

                                                DragHandle.BOTTOM_EDGE -> {
                                                    val anchorTop = cropTop
                                                    val centerX = cropLeft + cropSize / 2f
                                                    val delta = dragAmount.y
                                                    val maxAllowedFromBottom = imgRect.bottom - anchorTop
                                                    val maxAllowedFromWidth = min(centerX - imgRect.left, imgRect.right - centerX) * 2f
                                                    val maxAllowedSize = min(maxAllowedFromBottom, maxAllowedFromWidth)
                                                    val newSize = (cropSize + delta).coerceIn(minCropSizePx, maxAllowedSize)
                                                    cropSize = newSize
                                                    cropTop = anchorTop
                                                    cropLeft = (centerX - newSize / 2f).coerceIn(imgRect.left, imgRect.right - newSize)
                                                }

                                                DragHandle.LEFT_EDGE -> {
                                                    val anchorRight = cropLeft + cropSize
                                                    val centerY = cropTop + cropSize / 2f
                                                    val delta = -dragAmount.x
                                                    val maxAllowedFromLeft = anchorRight - imgRect.left
                                                    val maxAllowedFromHeight = min(centerY - imgRect.top, imgRect.bottom - centerY) * 2f
                                                    val maxAllowedSize = min(maxAllowedFromLeft, maxAllowedFromHeight)
                                                    val newSize = (cropSize + delta).coerceIn(minCropSizePx, maxAllowedSize)
                                                    cropSize = newSize
                                                    cropLeft = anchorRight - newSize
                                                    cropTop = (centerY - newSize / 2f).coerceIn(imgRect.top, imgRect.bottom - newSize)
                                                }

                                                DragHandle.RIGHT_EDGE -> {
                                                    val anchorLeft = cropLeft
                                                    val centerY = cropTop + cropSize / 2f
                                                    val delta = dragAmount.x
                                                    val maxAllowedFromRight = imgRect.right - anchorLeft
                                                    val maxAllowedFromHeight = min(centerY - imgRect.top, imgRect.bottom - centerY) * 2f
                                                    val maxAllowedSize = min(maxAllowedFromRight, maxAllowedFromHeight)
                                                    val newSize = (cropSize + delta).coerceIn(minCropSizePx, maxAllowedSize)
                                                    cropSize = newSize
                                                    cropLeft = anchorLeft
                                                    cropTop = (centerY - newSize / 2f).coerceIn(imgRect.top, imgRect.bottom - newSize)
                                                }

                                                DragHandle.NONE -> {}
                                            }
                                        }
                                    )
                                }
                        ) {
                            val currentCropRect = Rect(cropLeft, cropTop, cropLeft + cropSize, cropTop + cropSize)

                            // 1. Draw Stationary Image inside imgRect
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(imgRect.left.toInt(), imgRect.top.toInt()),
                                dstSize = IntSize(imgRect.width.toInt(), imgRect.height.toInt())
                            )

                            // 2. Dark Scrim over everything outside the crop window
                            val totalW = size.width
                            val totalH = size.height
                            val scrimPath = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(Rect(0f, 0f, totalW, totalH))
                                addRect(currentCropRect)
                            }
                            drawPath(scrimPath, color = Color.Black.copy(alpha = 0.65f))

                            // 3. Thin Crop Square Border
                            drawRect(
                                color = Color.White.copy(alpha = 0.55f),
                                topLeft = Offset(currentCropRect.left, currentCropRect.top),
                                size = Size(currentCropRect.width, currentCropRect.height),
                                style = Stroke(width = 1.dp.toPx())
                            )

                            // 4. WhatsApp 3x3 Grid
                            val thirdW = currentCropRect.width / 3f
                            val thirdH = currentCropRect.height / 3f
                            val gridColor = Color.White.copy(alpha = 0.45f)
                            val gridStroke = 0.8.dp.toPx()

                            // Vertical lines
                            drawLine(gridColor, Offset(currentCropRect.left + thirdW, currentCropRect.top), Offset(currentCropRect.left + thirdW, currentCropRect.bottom), gridStroke)
                            drawLine(gridColor, Offset(currentCropRect.left + thirdW * 2f, currentCropRect.top), Offset(currentCropRect.left + thirdW * 2f, currentCropRect.bottom), gridStroke)

                            // Horizontal lines
                            drawLine(gridColor, Offset(currentCropRect.left, currentCropRect.top + thirdH), Offset(currentCropRect.right, currentCropRect.top + thirdH), gridStroke)
                            drawLine(gridColor, Offset(currentCropRect.left, currentCropRect.top + thirdH * 2f), Offset(currentCropRect.right, currentCropRect.top + thirdH * 2f), gridStroke)

                            // 5. WhatsApp Thick White L-Corners
                            val cornerLen = 22.dp.toPx()
                            val cornerStroke = 3.5.dp.toPx()
                            val cornerColor = Color.White

                            // Top-Left Corner
                            drawLine(cornerColor, Offset(currentCropRect.left - cornerStroke / 2f, currentCropRect.top), Offset(currentCropRect.left + cornerLen, currentCropRect.top), cornerStroke)
                            drawLine(cornerColor, Offset(currentCropRect.left, currentCropRect.top - cornerStroke / 2f), Offset(currentCropRect.left, currentCropRect.top + cornerLen), cornerStroke)

                            // Top-Right Corner
                            drawLine(cornerColor, Offset(currentCropRect.right + cornerStroke / 2f, currentCropRect.top), Offset(currentCropRect.right - cornerLen, currentCropRect.top), cornerStroke)
                            drawLine(cornerColor, Offset(currentCropRect.right, currentCropRect.top - cornerStroke / 2f), Offset(currentCropRect.right, currentCropRect.top + cornerLen), cornerStroke)

                            // Bottom-Left Corner
                            drawLine(cornerColor, Offset(currentCropRect.left - cornerStroke / 2f, currentCropRect.bottom), Offset(currentCropRect.left + cornerLen, currentCropRect.bottom), cornerStroke)
                            drawLine(cornerColor, Offset(currentCropRect.left, currentCropRect.bottom + cornerStroke / 2f), Offset(currentCropRect.left, currentCropRect.bottom - cornerLen), cornerStroke)

                            // Bottom-Right Corner
                            drawLine(cornerColor, Offset(currentCropRect.right + cornerStroke / 2f, currentCropRect.bottom), Offset(currentCropRect.right - cornerLen, currentCropRect.bottom), cornerStroke)
                            drawLine(cornerColor, Offset(currentCropRect.right, currentCropRect.bottom + cornerStroke / 2f), Offset(currentCropRect.right, currentCropRect.bottom - cornerLen), cornerStroke)

                            // 6. WhatsApp Mid-Edge Ticks
                            val midTickLen = 16.dp.toPx()
                            val midTickStroke = 3.dp.toPx()

                            // Top Mid Tick
                            drawLine(cornerColor, Offset(currentCropRect.left + currentCropRect.width / 2f - midTickLen / 2f, currentCropRect.top), Offset(currentCropRect.left + currentCropRect.width / 2f + midTickLen / 2f, currentCropRect.top), midTickStroke)
                            // Bottom Mid Tick
                            drawLine(cornerColor, Offset(currentCropRect.left + currentCropRect.width / 2f - midTickLen / 2f, currentCropRect.bottom), Offset(currentCropRect.left + currentCropRect.width / 2f + midTickLen / 2f, currentCropRect.bottom), midTickStroke)
                            // Left Mid Tick
                            drawLine(cornerColor, Offset(currentCropRect.left, currentCropRect.top + currentCropRect.height / 2f - midTickLen / 2f), Offset(currentCropRect.left, currentCropRect.top + currentCropRect.height / 2f + midTickLen / 2f), midTickStroke)
                            // Right Mid Tick
                            drawLine(cornerColor, Offset(currentCropRect.right, currentCropRect.top + currentCropRect.height / 2f - midTickLen / 2f), Offset(currentCropRect.right, currentCropRect.top + currentCropRect.height / 2f + midTickLen / 2f), midTickStroke)
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
                        // Cancel
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

                        // Rotate 90 degrees
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

                        // Done
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
                                        if (sourceBitmap != null && !isCropping && cropSize > 0f && activeImgRect.width > 0f) {
                                            isCropping = true
                                            val normX = ((cropLeft - activeImgRect.left) / activeImgRect.width).coerceIn(0f, 1f)
                                            val normY = ((cropTop - activeImgRect.top) / activeImgRect.height).coerceIn(0f, 1f)
                                            val normSizeX = (cropSize / activeImgRect.width).coerceIn(0f, 1f)
                                            val normSizeY = (cropSize / activeImgRect.height).coerceIn(0f, 1f)

                                            coroutineScope.launch {
                                                val croppedUri = cropFromRotatedBitmap(
                                                    context = context,
                                                    source = sourceBitmap!!,
                                                    rotationDegrees = rotationDegrees,
                                                    normX = normX,
                                                    normY = normY,
                                                    normSizeX = normSizeX,
                                                    normSizeY = normSizeY
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
 * Executes high quality square crop from the user-selected crop boundaries on the stationary photo.
 */
private suspend fun cropFromRotatedBitmap(
    context: Context,
    source: Bitmap,
    rotationDegrees: Int,
    normX: Float,
    normY: Float,
    normSizeX: Float,
    normSizeY: Float
): Uri? = withContext(Dispatchers.IO) {
    try {
        val rotatedSource = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } else {
            source
        }

        val srcW = rotatedSource.width.toFloat()
        val srcH = rotatedSource.height.toFloat()

        val cropX = (normX * srcW).toInt().coerceIn(0, (srcW - 1).toInt())
        val cropY = (normY * srcH).toInt().coerceIn(0, (srcH - 1).toInt())
        val cropW = (normSizeX * srcW).toInt().coerceIn(1, (srcW - cropX).toInt())
        val cropH = (normSizeY * srcH).toInt().coerceIn(1, (srcH - cropY).toInt())
        val cropDim = min(cropW, cropH)

        val cropped = Bitmap.createBitmap(
            rotatedSource,
            cropX,
            cropY,
            cropDim,
            cropDim
        )

        // Scale to clean standard 512x512 square profile photo
        val targetSize = 512
        val scaledSquare = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)

        val outputDir = File(context.cacheDir, "cropped_avatars").apply { mkdirs() }
        val outputFile = File(outputDir, "whatsapp_avatar_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { out ->
            scaledSquare.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        Uri.fromFile(outputFile)
    } catch (e: Exception) {
        Log.e("WhatsAppCropper", "cropFromRotatedBitmap failed: ${e.message}", e)
        null
    }
}
