package com.caydey.ffshare

import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Utils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiscordSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val settings = Settings(context)
    private val utils = Utils(context)
    private lateinit var savedPreferences: Map<String, *>
    private val folders = mutableListOf<File>()

    @Before
    fun saveSettings() {
        savedPreferences = preferences.all.toMap()
        preferences.edit().clear().commit()
    }

    @After
    fun restoreSettings() {
        val editor = preferences.edit().clear()
        for ((key, value) in savedPreferences) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
        folders.forEach { it.deleteRecursively() }
    }

    @Test
    fun presetIsOptInAndDisabledModeKeepsOriginalFormats() {
        assertFalse(settings.discordUpload)
        for (type in listOf(Utils.MediaType.WEBM, Utils.MediaType.PNG, Utils.MediaType.GIF, Utils.MediaType.WAV)) {
            assertEquals(type, outputType(type))
        }
    }

    @Test
    fun presetOverridesFormatsWithoutOverwritingManualPreferences() {
        settings.videoCodec = Settings.VideoCodecOpts.COPY
        settings.audioCodec = Settings.AudioCodecOpts.COPY
        settings.videoMaxFileSize = 8192
        settings.customVideoParams = "-c copy"
        settings.customImageParams = "-c:v png"
        settings.customAudioParams = "-c:a copy"
        settings.imageConversionOutput = Utils.MediaType.PNG
        settings.audioConversionOutput = Utils.MediaType.WAV
        settings.copyExifTags = true
        val manualValues = preferences.all.toMap()

        settings.discordUpload = true
        for (type in listOf(Utils.MediaType.WEBM, Utils.MediaType.MKV, Utils.MediaType.GIF)) {
            assertEquals(Utils.MediaType.MP4, outputType(type))
        }
        assertEquals(Utils.MediaType.WEBP, outputType(Utils.MediaType.PNG))
        assertEquals(Utils.MediaType.AAC, outputType(Utils.MediaType.WAV))
        assertEquals(manualValues, preferences.all.filterKeys { it != Settings.DISCORD_UPLOAD })

        settings.discordUpload = false
        assertEquals(Utils.MediaType.WEBM, outputType(Utils.MediaType.WEBM))
        assertEquals(Utils.MediaType.PNG, outputType(Utils.MediaType.JPEG))
        assertEquals(Utils.MediaType.WAV, outputType(Utils.MediaType.MP3))
    }

    private fun outputType(type: Utils.MediaType): Utils.MediaType {
        val (output, result) = utils.getCacheOutputFile(Uri.EMPTY, type)
        folders.add(output.parentFile!!)
        assertTrue(output.name.endsWith(".${result.name.lowercase()}"))
        return result
    }
}
