package com.example.ui.webrtc

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.webrtc.WebRtcClient
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun LocalVideoView(
    webRtcClient: WebRtcClient,
    isMirror: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    DisposableEffect(webRtcClient) {
        webRtcClient.initLocalSurfaceView(renderer)
        renderer.setMirror(isMirror)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        onDispose {
            try {
                renderer.release()
            } catch (e: Exception) {
                // Ignore release errors on dispose
            }
        }
    }

    DisposableEffect(isMirror) {
        renderer.setMirror(isMirror)
        onDispose {}
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier
    )
}

@Composable
fun RemoteVideoView(
    webRtcClient: WebRtcClient,
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    DisposableEffect(webRtcClient) {
        try {
            renderer.init(webRtcClient.rootEglBase.eglBaseContext, null)
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(false)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        } catch (e: Exception) {
            // Already initialized or context issue
        }

        onDispose {
            try {
                renderer.release()
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }

    DisposableEffect(videoTrack) {
        if (videoTrack != null) {
            videoTrack.addSink(renderer)
        }
        onDispose {
            if (videoTrack != null) {
                try {
                    videoTrack.removeSink(renderer)
                } catch (e: Exception) {
                    // Ignore remove sink errors
                }
            }
        }
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier
    )
}
