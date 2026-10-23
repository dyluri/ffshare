package com.caydey.ffshare.utils

import kotlin.math.min
import kotlin.math.pow

class DiscordUploadTarget(
    private val kind: Kind,
    private val durationSeconds: Double,
    private val hasAudio: Boolean
) {
    enum class Kind { VIDEO, AUDIO, IMAGE }

    fun parameters(attempt: Int, budgetScale: Double = 1.0): String? {
        require(attempt in 0 until MAX_ATTEMPTS)
        require(budgetScale.isFinite() && budgetScale > 0 && budgetScale <= 1)
        val metadata = "-map_metadata -1 -map_chapters -1 -sn -dn"
        if (kind == Kind.IMAGE) {
            val edge = (4096 * 0.65.pow(attempt)).toInt()
            val quality = 85 - attempt * 20
            return "-map 0:v:0 $metadata -frames:v 1 -c:v libwebp -quality $quality " +
                "-compression_level 6 -vf \"${scale(edge)}\""
        }

        if (!durationSeconds.isFinite() || durationSeconds <= 0) return null
        // Reserve 5% for muxing overhead. Never use -fs: it can truncate the media.
        val totalKbps = (LIMIT_BYTES * 0.95 * 8 * budgetScale / durationSeconds / 1000).toInt()
        if (kind == Kind.AUDIO) {
            if (totalKbps < MIN_AUDIO_KBPS) return null
            return "-map 0:a:0 $metadata -vn -c:a aac -ac 2 -b:a ${min(totalKbps, 192)}k"
        }

        val audioKbps = if (hasAudio) (totalKbps / 5).coerceIn(MIN_AUDIO_KBPS, 128) else 0
        val videoKbps = min(totalKbps - audioKbps, 20_000)
        if (videoKbps < MIN_VIDEO_KBPS) return null
        val baseEdge = when {
            videoKbps >= 2500 -> 1920
            videoKbps >= 1000 -> 1280
            videoKbps >= 400 -> 854
            else -> 640
        }
        val edge = (baseEdge * 0.75.pow(attempt)).toInt()
        val fps = when {
            videoKbps < 400 || attempt == 2 -> 15
            videoKbps < 1000 || attempt == 1 -> 24
            else -> 30
        }
        val audio = if (hasAudio) "-map 0:a:0? -c:a aac -ac 2 -b:a ${audioKbps}k" else "-an"
        // SAF output is write-only; faststart needs to reopen it for reading.
        return "-map 0:v:0 $audio $metadata -c:v libx264 -preset medium " +
            "-b:v ${videoKbps}k -maxrate ${videoKbps}k -bufsize ${videoKbps * 2}k " +
            "-fpsmax $fps -vf \"pad=ceil(iw/2)*2:ceil(ih/2)*2,${scale(edge)},pad=ceil(iw/2)*2:ceil(ih/2)*2,setsar=1,format=yuv420p\""
    }

    private fun scale(edge: Int) =
        "scale=w='min(iw,$edge)':h='min(ih,$edge)':force_original_aspect_ratio=decrease"

    companion object {
        const val LIMIT_BYTES = 20_000_000L
        const val MAX_ATTEMPTS = 3
        private const val MIN_AUDIO_KBPS = 24
        private const val MIN_VIDEO_KBPS = 150

        fun accepts(size: Long) = size > 0 && size < LIMIT_BYTES

        fun nextBudgetScale(current: Double, actualSize: Long): Double {
            require(actualSize >= LIMIT_BYTES)
            return current * min(0.8, LIMIT_BYTES.toDouble() * 0.9 / actualSize)
        }
    }
}
