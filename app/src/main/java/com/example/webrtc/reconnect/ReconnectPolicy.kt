package com.example.webrtc.reconnect

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.random.Random

/**
 * Strategy mode used during reconnection (matching LiveKit's two reconnect strategies)
 */
enum class ReconnectMode {
    /**
     * Fast path: Keeps WebRTC PeerConnection, local media streams and remote tracks intact.
     * Re-verifies signaling and issues an ICE restart renegotiation without rebuilding.
     */
    RESUME,

    /**
     * Fallback path: Tearing down stale PeerConnection cleanly and rebuilding from scratch.
     * Re-publishes local audio & video tracks and re-subscribes.
     */
    FULL_RECONNECT
}

/**
 * Reason triggering the reconnection attempt
 */
enum class DisconnectReason {
    PEER_CONNECTION_DISCONNECTED,
    PEER_CONNECTION_FAILED,
    ICE_DISCONNECTED,
    ICE_FAILED,
    SIGNALING_DISCONNECTED,
    MANUAL_TRIGGER,
    PEER_REQUESTED,
    UNKNOWN
}

/**
 * Context provided to [ReconnectPolicy.getNextRetryDelay] on each reconnect step.
 */
data class ReconnectContext(
    val attemptIndex: Int,
    val retryCount: Int,
    val mode: ReconnectMode,
    val reason: DisconnectReason,
    val cumulativeElapsedTimeMs: Long
)

/**
 * Interface governing retries and backoff delays for reconnection.
 */
interface ReconnectPolicy {
    /**
     * Computes how long to wait before the next reconnection attempt.
     * Returns null if reconnection should be aborted entirely.
     */
    fun getNextRetryDelay(context: ReconnectContext): Duration?
}

/**
 * Default production-grade reconnect policy with exponential backoff, jitter,
 * and a hard cap of 30 total retries (LiveKit standard).
 *
 * Fast Resume attempts: Up to 3 rapid attempts (~300ms, 600ms, 1200ms)
 * Full Reconnect fallback attempts: Exponential backoff (1s, 2s, 3s, 5s... max 8s) up to 30 attempts.
 */
class DefaultReconnectPolicy(
    private val maxAttempts: Int = 30,
    private val maxFastResumeAttempts: Int = 3,
    private val maxDelayMs: Long = 8000L
) : ReconnectPolicy {

    override fun getNextRetryDelay(context: ReconnectContext): Duration? {
        if (context.retryCount >= maxAttempts) {
            return null
        }

        return when (context.mode) {
            ReconnectMode.RESUME -> {
                if (context.retryCount >= maxFastResumeAttempts) {
                    // Transition to Full Reconnect fallback
                    null
                } else {
                    // Fast path backoff: 300ms, 600ms, 1200ms + random jitter
                    val base = (300L * (1 shl context.retryCount)).coerceAtMost(2000L)
                    val jitter = Random.nextLong(50, 150)
                    (base + jitter).milliseconds
                }
            }
            ReconnectMode.FULL_RECONNECT -> {
                // Fallback exponential backoff: 1s, 2s, 4s... capped at maxDelayMs with 20% jitter
                val step = (context.retryCount - maxFastResumeAttempts).coerceAtLeast(0)
                val base = (1000L * (1 shl step.coerceAtMost(3))).coerceAtMost(maxDelayMs)
                val jitter = Random.nextLong(100, 400)
                (base + jitter).milliseconds
            }
        }
    }
}
