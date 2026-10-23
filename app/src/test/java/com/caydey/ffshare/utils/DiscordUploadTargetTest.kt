package com.caydey.ffshare.utils

import org.junit.Assert.*
import org.junit.Test

class DiscordUploadTargetTest {
    @Test
    fun limitIsDecimalMegabytesAndRejectsEmptyOrOversizedFiles() {
        assertEquals(20_000_000L, DiscordUploadTarget.LIMIT_BYTES)
        assertFalse(DiscordUploadTarget.accepts(0))
        assertFalse(DiscordUploadTarget.accepts(-1))
        assertTrue(DiscordUploadTarget.accepts(19_999_999))
        assertFalse(DiscordUploadTarget.accepts(20_000_000))
        assertFalse(DiscordUploadTarget.accepts(20_971_520))
    }

    @Test
    fun videoBudgetIncludesAudioAndFivePercentOverhead() {
        val params = video(100.0).parameters(0)!!
        assertTrue(params.contains("-b:v 1392k"))
        assertTrue(params.contains("-b:a 128k"))
        assertTrue(params.contains("-c:a aac"))
        assertTrue(params.contains("-c:v libx264"))
        assertFalse(params.contains("-crf"))
        assertFalse(params.contains("-fs "))
        assertFalse(params.contains("copy"))
    }

    @Test
    fun silentVideoUsesWholeBudgetAndDoesNotAddAudio() {
        val params = video(100.0, false).parameters(0)!!
        assertTrue(params.contains("-b:v 1520k"))
        assertTrue(params.contains("-an"))
        assertFalse(params.contains("-b:a"))
    }

    @Test
    fun shortVideosHaveABoundedBitrate() {
        val params = video(0.001).parameters(0)!!
        assertTrue(params.contains("-b:v 20000k"))
        assertTrue(params.contains("-bufsize 40000k"))
    }

    @Test
    fun retriesLowerBitrateResolutionAndFrameRate() {
        val target = video(40.0)
        val first = target.parameters(0)!!
        val second = target.parameters(1, 0.8)!!
        val third = target.parameters(2, 0.64)!!
        assertTrue(first.contains("min(iw,1920)"))
        assertTrue(first.contains("-fpsmax 30"))
        assertTrue(second.contains("min(iw,1440)"))
        assertTrue(second.contains("-fpsmax 24"))
        assertTrue(third.contains("min(iw,720)"))
        assertTrue(third.contains("-fpsmax 15"))
        assertTrue(second.contains("-b:v 2912k"))
        assertTrue(third.contains("-b:v 2304k"))
    }

    @Test
    fun lowBitrateVideoUsesSmallerFramesAndLowerAudioBitrate() {
        val params = video(600.0).parameters(0)!!
        assertTrue(params.contains("-b:a 50k"))
        assertTrue(params.contains("min(iw,640)"))
        assertTrue(params.contains("-fpsmax 15"))
    }

    @Test
    fun extremeDurationsFailRatherThanUseUnusableOrNegativeBitrates() {
        assertNull(video(10_000.0).parameters(0))
        assertNull(audio(10_000.0).parameters(0))
        for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(video(duration).parameters(0))
            assertNull(audio(duration).parameters(0))
        }
    }

    @Test
    fun audioBudgetAdaptsWithoutCopyingTheInputCodec() {
        assertTrue(audio(60.0).parameters(0)!!.contains("-b:a 192k"))
        assertTrue(audio(1000.0).parameters(0)!!.contains("-b:a 152k"))
        assertTrue(audio(1000.0).parameters(1, 0.8)!!.contains("-b:a 121k"))
        assertTrue(audio(60.0).parameters(0)!!.contains("-vn -c:a aac"))
    }

    @Test
    fun imagesDoNotNeedDurationAndRetriesReduceQualityAndDimensions() {
        val target = DiscordUploadTarget(DiscordUploadTarget.Kind.IMAGE, Double.NaN, false)
        assertTrue(target.parameters(0)!!.contains("-quality 85"))
        assertTrue(target.parameters(0)!!.contains("min(iw,4096)"))
        assertTrue(target.parameters(1)!!.contains("-quality 65"))
        assertTrue(target.parameters(1)!!.contains("min(iw,2662)"))
        assertTrue(target.parameters(2)!!.contains("-quality 45"))
        assertTrue(target.parameters(2)!!.contains("-c:v libwebp"))
    }

    @Test
    fun measuredOvershootReducesTheNextBudgetProportionally() {
        assertEquals(0.8, DiscordUploadTarget.nextBudgetScale(1.0, 20_000_000), 0.00001)
        assertEquals(0.45, DiscordUploadTarget.nextBudgetScale(1.0, 40_000_000), 0.00001)
        assertEquals(0.36, DiscordUploadTarget.nextBudgetScale(0.8, 40_000_000), 0.00001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun retriesAreBounded() {
        video(60.0).parameters(DiscordUploadTarget.MAX_ATTEMPTS)
    }

    @Test
    fun videoPadsOddDimensionsAndDoesNotUpscaleSmallInputs() {
        val params = video(60.0).parameters(0)!!
        assertTrue(params.contains("pad=ceil(iw/2)*2:ceil(ih/2)*2"))
        assertTrue(params.contains("min(iw,"))
        assertTrue(params.contains("min(ih,"))
        assertTrue(params.contains("force_original_aspect_ratio=decrease"))
        assertTrue(params.contains("format=yuv420p"))
        assertTrue(params.contains("-map_metadata -1"))
    }

    @Test
    fun videoDoesNotRequireReadingBackWriteOnlySafOutput() {
        for (hasAudio in listOf(true, false)) {
            for (attempt in 0 until DiscordUploadTarget.MAX_ATTEMPTS) {
                val params = video(60.0, hasAudio).parameters(attempt)!!
                assertFalse(params.contains("faststart"))
            }
        }
    }

    private fun video(duration: Double, hasAudio: Boolean = true) =
        DiscordUploadTarget(DiscordUploadTarget.Kind.VIDEO, duration, hasAudio)

    private fun audio(duration: Double) =
        DiscordUploadTarget(DiscordUploadTarget.Kind.AUDIO, duration, true)
}
