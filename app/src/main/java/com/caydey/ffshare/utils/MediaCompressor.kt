package com.caydey.ffshare.utils


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.caydey.ffshare.R
import com.caydey.ffshare.utils.logs.Log
import com.caydey.ffshare.utils.logs.LogsDbHelper
import timber.log.Timber
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


class MediaCompressor(private val context: Context) {
    private val utils: Utils by lazy { Utils(context) }
    private val settings: Settings by lazy { Settings(context) }
    private val logsDbHelper by lazy { LogsDbHelper(context) }

    private val ffmpegParamMaker = FFmpegParamMaker(settings, utils)
    private val operationLock = Any()
    private var cancelCurrentOperation: (() -> Unit)? = null
    private val cancelled = AtomicBoolean(false)

    fun cancelAllOperations() {
        synchronized(operationLock) {
            Timber.d("Canceling all ffmpeg operations")
            cancelled.set(true)
            FFmpegKit.cancel()
            cancelCurrentOperation?.invoke()
        }
    }

    @SuppressLint("SetTextI18n")
    fun compressSingleFile(
        activity: Activity,
        inputFileUri: Uri,
        successHandler: (uri: Uri, inputFileSize: Long, outputFileSize: Long) -> Unit,
        failureHandler: () -> Unit
    ) {
        val txtFfmpegCommand: TextView = activity.findViewById(R.id.txtFfmpegCommand)
        val txtInputFile: TextView = activity.findViewById(R.id.txtInputFile)
        val txtInputFileSize: TextView = activity.findViewById(R.id.txtInputFileSize)
        val txtOutputFile: TextView = activity.findViewById(R.id.txtOutputFile)
        val txtOutputFileSize: TextView = activity.findViewById(R.id.txtOutputFileSize)
        val txtProcessedTime: TextView = activity.findViewById(R.id.txtProcessedTime)
        val txtProcessedTimeTotal: TextView = activity.findViewById(R.id.txtProcessedTimeTotal)
        val txtProcessedPercent: TextView = activity.findViewById(R.id.txtProcessedPercent)
        val processedTableRow: TableRow = activity.findViewById(R.id.processedTableRow)

        // cancel button
        val finished = AtomicBoolean(false)
        fun finishFailure() {
            val notify = synchronized(operationLock) {
                if (finished.compareAndSet(false, true)) {
                    cancelCurrentOperation = null
                    true
                } else false
            }
            if (notify) Handler(Looper.getMainLooper()).post { failureHandler() }
        }
        synchronized(operationLock) {
            if (cancelled.get()) {
                finishFailure()
                return
            }
            cancelCurrentOperation = { finishFailure() }
        }
        val btnCancel: Button = activity.findViewById(R.id.btnCancel)
        Handler(Looper.getMainLooper()).post {
            btnCancel.setOnClickListener {
                synchronized(operationLock) {
                    if (!finished.get()) {
                        Toast.makeText(context, context.getString(R.string.ffmpeg_canceled), Toast.LENGTH_LONG).show()
                        cancelAllOperations()
                    }
                }
            }
        }

        val mediaType = utils.getMediaType(inputFileUri)
        if (!utils.isSupportedMediaType(mediaType)) { // not supported show error
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.error_unknown_filetype), Toast.LENGTH_LONG).show()
            }
            finishFailure()
            return
        }

        val discordUpload = settings.discordUpload
        // don't show progress when compressing images (not possible)
        val showProgress = !utils.isImage(mediaType) || (discordUpload && mediaType == Utils.MediaType.GIF)
        Handler(Looper.getMainLooper()).post {
            processedTableRow.visibility = if (showProgress) View.VISIBLE else View.INVISIBLE
        }

        val inputFileName = utils.getFilenameFromUri(inputFileUri) ?: "unknown"

        // get output file, (random uuid, custom name, original name)
        val (outputFile, outputMediaType) = utils.getCacheOutputFile(inputFileUri, mediaType)

        // get Uri from File, needs to be this way not Uri.fromFile(...) to go through security
        val outputFileUri = FileProvider.getUriForFile(context, context.applicationContext.packageName+".fileprovider", outputFile)

        // need to create new saf param as they are one-use
        val mediaInformation = FFprobeKit.getMediaInformation(FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)).getMediaInformation()

        if (finished.get()) {
            outputFile.delete()
            return
        }
        if (mediaInformation == null) {
            Timber.d("Unable to get media information, throwing error")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.error_invalid_file), Toast.LENGTH_LONG).show()
            }
            finishFailure()
            return
        }

        val inputFileSize = mediaInformation.getSize()?.toLong() ?: 0 // get input file size

        var duration = 0 // default duration for image
        if (showProgress) {
            // invalid video file if ffprobe cant parse duration and size
            if ((!discordUpload && mediaInformation.getSize() == null) || mediaInformation.getDuration() == null) {
                Timber.d("Unable to get size & duration for media, throwing error")
                val message = if (discordUpload) R.string.discord_upload_failed else R.string.error_invalid_file
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
                }
                finishFailure()
                return
            }
            duration = ((mediaInformation.getDuration()?.toFloat() ?: 0f) * 1_000).toInt()
        }

        val discordTarget = if (discordUpload) DiscordUploadTarget(
            when {
                utils.isVideo(outputMediaType) -> DiscordUploadTarget.Kind.VIDEO
                utils.isAudio(outputMediaType) -> DiscordUploadTarget.Kind.AUDIO
                else -> DiscordUploadTarget.Kind.IMAGE
            },
            mediaInformation.getDuration()?.toDoubleOrNull() ?: Double.NaN,
            mediaInformation.getStreams().any { it.getType() == "audio" }
        ) else null

        fun fail(message: Int) {
            if (finished.compareAndSet(false, true)) {
                cancelCurrentOperation = null
                outputFile.delete()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
                    failureHandler()
                }
            }
        }

        fun runAttempt(attempt: Int, budgetScale: Double) {
            synchronized(operationLock) {
                if (finished.get()) return
                val params = if (discordTarget != null) discordTarget.parameters(attempt, budgetScale)
                    else ffmpegParamMaker.create(inputFileUri, mediaInformation, mediaType, outputMediaType)
                if (params == null) {
                    fail(R.string.discord_upload_failed)
                    return
                }
                // SAF parameters are one-use; each retry reads the original source.
                val inputSaf = FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)
                val outputSaf = FFmpegKitConfig.getSafParameterForWrite(context, outputFileUri)
                val command = "-y -i $inputSaf $params $outputSaf"
                val prettyCommand = "ffmpeg -y -i $inputFileName $params ${outputFile.name}"
                Handler(Looper.getMainLooper()).post {
                    txtFfmpegCommand.text = prettyCommand
                    txtInputFile.text = inputFileName
                    txtInputFileSize.text = utils.bytesToHuman(inputFileSize)
                    txtOutputFile.text = outputFile.name
                    txtOutputFileSize.text = utils.bytesToHuman(0)
                    txtProcessedTime.text = utils.millisToMicrowaveTime(0)
                    txtProcessedTimeTotal.text = utils.millisToMicrowaveTime(duration)
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, 0.0f)
                }

                Timber.d("Executing ffmpeg command (attempt %d): 'ffmpeg %s'", attempt + 1, command)
                FFmpegKit.executeAsync(command, { session ->
                    var completedSize: Long? = null
                    synchronized(operationLock) {
                        if (finished.get()) {
                            outputFile.delete()
                            return@executeAsync
                        }
                        if (session.getReturnCode()?.isValueSuccess() != true) {
                            logsDbHelper.addLog(Log(
                                prettyCommand, inputFileName, outputFile.name, false,
                                session.getOutput(), inputFileSize, -1
                            ))
                            fail(if (session.getReturnCode()?.isValueCancel() == true)
                                R.string.ffmpeg_canceled else R.string.ffmpeg_error)
                            return@executeAsync
                        }
                        if (!discordUpload && settings.copyExifTags && ExifTools.isValidType(mediaType)) {
                            ExifTools.copyExif(context.contentResolver.openInputStream(inputFileUri)!!, outputFile)
                        }
                        val outputSize = outputFile.length()
                        val accepted = outputSize > 0 && (discordTarget == null || DiscordUploadTarget.accepts(outputSize))
                        logsDbHelper.addLog(Log(
                            prettyCommand, inputFileName, outputFile.name, accepted,
                            session.getOutput(), inputFileSize, outputSize
                        ))
                        if (!accepted) {
                            outputFile.delete()
                            if (discordTarget != null && outputSize >= DiscordUploadTarget.LIMIT_BYTES &&
                                attempt + 1 < DiscordUploadTarget.MAX_ATTEMPTS) {
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(context, context.getString(
                                        R.string.discord_upload_retry, attempt + 2, DiscordUploadTarget.MAX_ATTEMPTS
                                    ), Toast.LENGTH_LONG).show()
                                }
                                runAttempt(attempt + 1, DiscordUploadTarget.nextBudgetScale(budgetScale, outputSize))
                            } else {
                                fail(if (discordTarget != null) R.string.discord_upload_failed else R.string.ffmpeg_error)
                            }
                            return@executeAsync
                        }
                        finished.set(true)
                        cancelCurrentOperation = null
                        Handler(Looper.getMainLooper()).post {
                            txtProcessedPercent.text = context.getString(R.string.format_percentage, 100.0f)
                            txtProcessedTime.text = utils.millisToMicrowaveTime(duration)
                            txtOutputFileSize.text = utils.bytesToHuman(outputSize)
                        }
                        completedSize = outputSize
                    }
                    completedSize?.let { successHandler(outputFileUri, inputFileSize, it) }
                }, { /* logs */ }, { statistics ->
                    Handler(Looper.getMainLooper()).post {
                        if (!finished.get()) {
                            if (showProgress && duration > 0) {
                                txtProcessedPercent.text = context.getString(
                                    R.string.format_percentage, (statistics.time.toFloat() / duration * 100).coerceIn(0f, 100f)
                                )
                                txtProcessedTime.text = utils.millisToMicrowaveTime(statistics.time.toInt())
                            }
                            txtOutputFileSize.text = utils.bytesToHuman(statistics.size)
                        }
                    }
                })
            }
        }
        runAttempt(0, 1.0)
    }

    fun compressFiles(activity: Activity, inputFilesUri: ArrayList<Uri>, callback: (uris: ArrayList<Uri>) -> Unit) {
        cancelled.set(false)
        val txtCommandNumber: TextView = activity.findViewById(R.id.txtCommandNumber)

        val inputFilesCount = inputFilesUri.size

        val compressedFiles = ArrayList<Uri>()

        var totalInputFileSize = 0L
        var totalOutputFileSize = 0L

        // since we are working with callbacks a simple for loop wont work
        lateinit var iteratorFunction: (Int, Boolean) -> Unit
        iteratorFunction = fun(i, error) {
            if (error || cancelled.get()) {
                Handler(Looper.getMainLooper()).post {
                    callback(if (cancelled.get()) ArrayList() else compressedFiles)
                }
                return
            }
            // base case
            if (i < inputFilesCount) {
                Timber.d("Processing %d of %d files", i+1, inputFilesCount)

                if (inputFilesCount > 1) { // show "1 of N" label if N > 1
                    Handler(Looper.getMainLooper()).post {
                        txtCommandNumber.text = context.getString(R.string.command_x_of_y, i+1, inputFilesCount)
                    }
                }

                compressSingleFile(activity, inputFilesUri[i], failureHandler = {
                    // if 1 file fails don't add it to compressedFiles array
                    iteratorFunction(i+1, true) // call to self with error flag true
                }, successHandler = { uri, inputFileSize, outputFileSize ->
                    totalInputFileSize += inputFileSize
                    totalOutputFileSize += outputFileSize
                    compressedFiles.add(uri)
                    iteratorFunction(i+1, false) // call to self with error flag false
                })
            }
            if (i >= inputFilesCount || error) { // end of iterations
                // show compression percentage as toast message if there was no error
                if (settings.showStatusMessages && !error) {
                    val totalOutputFileSizeHuman = utils.bytesToHuman(totalOutputFileSize)
                    val compressionPercentage = (1 - (totalOutputFileSize.toDouble() / totalInputFileSize)) * 100
                    val toastMessage = context.getString(R.string.media_reduction_message, totalOutputFileSizeHuman, compressionPercentage)
                    Handler(Looper.getMainLooper()).post {
                        Timber.d("Showing compression size toast message")
                        Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    callback(if (cancelled.get()) ArrayList() else compressedFiles)
                }
            }
        }
        iteratorFunction(0, false) // start iterations
    }

}
