package com.caydey.ffshare.utils

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class DiscordUploadEncodingTest {
    @get:Rule
    val files = TemporaryFolder()

    @Before
    fun requireHostFfmpeg() {
        assumeTrue("Host FFmpeg and FFprobe are required for encoding tests",
            available("ffmpeg") && available("ffprobe"))
    }

    @Test
    fun videoWithAudioKeepsItsDurationAndDecodes() {
        val input = file("source.mkv")
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=60:d=2",
            "-f", "lavfi", "-i", "sine=d=2", "-c:v", "ffv1", "-c:a", "pcm_s16le", input.path)
        val target = DiscordUploadTarget(DiscordUploadTarget.Kind.VIDEO, 100.0, true)
        for (attempt in 0 until DiscordUploadTarget.MAX_ATTEMPTS) {
            val output = file("video-$attempt.mp4")
            encode(input, output, target.parameters(attempt, Math.pow(0.8, attempt.toDouble()))!!)
            assertTrue(probe(output, "stream=codec_name").contains("h264"))
            assertTrue(probe(output, "stream=codec_name").contains("aac"))
            assertEquals(2.0, probe(output, "format=duration").trim().toDouble(), 0.15)
        }
    }

    @Test
    fun silentOddPortraitVideoHasEvenDimensionsAndNoAddedAudio() {
        val input = file("portrait.mkv")
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=s=241x409:r=15:d=1",
            "-c:v", "ffv1", input.path)
        val output = encode(input, "portrait.mp4", DiscordUploadTarget.Kind.VIDEO, 1.0, false)
        val dimensions = probe(output, "stream=width,height").trim().lines().map { it.toInt() }
        assertEquals(listOf(242, 410), dimensions)
        assertFalse(probe(output, "stream=codec_name").contains("aac"))
    }

    @Test
    fun singlePixelVideoCanBeEncoded() {
        val input = file("tiny.mkv")
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=s=1x1:r=1:d=1",
            "-c:v", "ffv1", input.path)
        val output = encode(input, "tiny.mp4", DiscordUploadTarget.Kind.VIDEO, 1.0, false)
        assertEquals(listOf("2", "2"), probe(output, "stream=width,height").trim().lines())
    }

    @Test
    fun gifRetainsAnimationWhenConvertedToVideo() {
        val input = file("animated.gif")
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=s=160x120:r=10:d=1", input.path)
        val output = encode(input, "animation.mp4", DiscordUploadTarget.Kind.VIDEO, 1.0, false)
        assertTrue(probe(output, "stream=nb_frames").trim().toInt() > 1)
        assertEquals(1.0, probe(output, "format=duration").trim().toDouble(), 0.15)
    }

    @Test
    fun staticImagesKeepTransparencyAndReduceDimensionsOnRetry() {
        val input = file("transparent.png")
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=red@0.5:s=3000x2000,format=rgba",
            "-frames:v", "1", input.path)
        val target = DiscordUploadTarget(DiscordUploadTarget.Kind.IMAGE, Double.NaN, false)
        for (attempt in 0 until DiscordUploadTarget.MAX_ATTEMPTS) {
            val output = file("image-$attempt.webp")
            encode(input, output, target.parameters(attempt)!!)
            assertTrue(probe(output, "stream=pix_fmt").contains("yuva"))
            val width = probe(output, "stream=width").trim().toInt()
            assertTrue(width <= if (attempt == 0) 3000 else if (attempt == 1) 2662 else 1730)
        }
    }

    @Test
    fun audioTranscodesAndDecodesAtEveryRetrySetting() {
        val input = file("source.wav")
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=d=2", input.path)
        val target = DiscordUploadTarget(DiscordUploadTarget.Kind.AUDIO, 1000.0, true)
        for (attempt in 0 until DiscordUploadTarget.MAX_ATTEMPTS) {
            val output = file("audio-$attempt.aac")
            encode(input, output, target.parameters(attempt, Math.pow(0.8, attempt.toDouble()))!!)
            assertTrue(probe(output, "stream=codec_name").contains("aac"))
            assertEquals(2.0, probe(output, "format=duration").trim().toDouble(), 0.2)
        }
    }

    private fun encode(input: File, name: String, kind: DiscordUploadTarget.Kind, duration: Double, audio: Boolean): File {
        val output = file(name)
        encode(input, output, DiscordUploadTarget(kind, duration, audio).parameters(0)!!)
        return output
    }

    private fun encode(input: File, output: File, parameters: String) {
        val arguments = Regex("\"([^\"]*)\"|(\\S+)").findAll(parameters)
            .map { it.groups[1]?.value ?: it.groups[2]!!.value }.toList()
        run(*(listOf("ffmpeg", "-v", "error", "-i", input.path) + arguments + output.path).toTypedArray())
        assertTrue("Output must be nonempty and under 20 MB", DiscordUploadTarget.accepts(output.length()))
        run("ffmpeg", "-v", "error", "-i", output.path, "-f", "null", "-")
    }

    private fun probe(input: File, entries: String) =
        run("ffprobe", "-v", "error", "-show_entries", entries, "-of", "default=nw=1:nk=1", input.path)

    private fun file(name: String) = File(files.root, name)

    private fun available(tool: String) = try {
        val log = File.createTempFile("ffmpeg-version-", ".log", files.root)
        val process = ProcessBuilder(tool, "-version").redirectErrorStream(true).redirectOutput(log).start()
        if (process.waitFor(10, TimeUnit.SECONDS)) {
            process.exitValue() == 0
        } else {
            process.destroyForcibly()
            false
        }
    } catch (_: java.io.IOException) {
        false
    }

    private fun run(vararg arguments: String): String {
        val log = File.createTempFile("ffmpeg-", ".log", files.root)
        val process = ProcessBuilder(*arguments).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("FFmpeg timed out: ${arguments.joinToString(" ")}")
        }
        val output = log.readText()
        assertEquals(output, 0, process.exitValue())
        return output
    }
}
