package com.emjaycruz.coaf.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.roundToInt

class AudioDecoder(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TARGET_RATE = 44100
        private const val TARGET_CHANNELS = 2
        private const val TARGET_BITS = 16

        private const val MAX_OUTPUT_BYTES = 500_000_000L
        private const val MAX_NO_PROGRESS_MS = 15_000L
        private const val MAX_DECODE_TIME_MS = 120_000L

        private const val PCM_16_BIT = 2
        private const val PCM_8_BIT = 3
        private const val PCM_FLOAT = 4
    }

    fun decodeToWav(source: Uri, output: File): File {
        val parent = output.parentFile
            ?: error("Audio output directory is unavailable.")

        require(parent.exists() || parent.mkdirs()) {
            "Cannot create audio output directory."
        }

        val rawFile = File(parent, "${output.name}.raw")
        val convertedFile = File(parent, "${output.name}.converted")

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            output.delete()
            rawFile.delete()
            convertedFile.delete()

            extractor = MediaExtractor()
            extractor.setDataSource(appContext, source, null)

            var selectedTrack = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("audio/")) {
                    selectedTrack = i
                    inputFormat = format
                    break
                }
            }

            require(selectedTrack >= 0 && inputFormat != null) {
                "No audio track was found in this file."
            }

            extractor.selectTrack(selectedTrack)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Unknown audio format.")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sourceRate = inputFormat.getInteger(
                MediaFormat.KEY_SAMPLE_RATE
            )
            var sourceChannels = inputFormat.getInteger(
                MediaFormat.KEY_CHANNEL_COUNT
            )
            var pcmEncoding = PCM_16_BIT

            var inputDone = false
            var outputDone = false
            var totalBytes = 0L

            val startTime = System.nanoTime()
            var lastProgressTime = startTime

            fun checkProgress() {
                val now = System.nanoTime()

                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    now - startTime
                )
                val stalledMs = TimeUnit.NANOSECONDS.toMillis(
                    now - lastProgressTime
                )

                check(elapsedMs < MAX_DECODE_TIME_MS) {
                    "Audio decoding took too long. Try a shorter MP3."
                }

                check(stalledMs < MAX_NO_PROGRESS_MS) {
                    "Audio decoding stalled. Try another MP3 file."
                }
            }

            BufferedOutputStream(
                FileOutputStream(rawFile),
                64 * 1024
            ).use { stream ->

                val info = MediaCodec.BufferInfo()

                while (!outputDone) {
                    checkProgress()

                    var madeProgress = false

                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)

                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                                ?: error("Cannot access decoder input buffer.")

                            inputBuffer.clear()

                            val sampleSize = extractor.readSampleData(
                                inputBuffer,
                                0
                            )

                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }

                            madeProgress = true
                        }
                    }

                    when (
                        val index = codec.dequeueOutputBuffer(info, 10_000)
                    ) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat

                            sourceRate = format.getInteger(
                                MediaFormat.KEY_SAMPLE_RATE
                            )
                            sourceChannels = format.getInteger(
                                MediaFormat.KEY_CHANNEL_COUNT
                            )

                            pcmEncoding =
                                if (format.containsKey(
                                        MediaFormat.KEY_PCM_ENCODING
                                    )
                                ) {
                                    format.getInteger(
                                        MediaFormat.KEY_PCM_ENCODING
                                    )
                                } else {
                                    PCM_16_BIT
                                }

                            madeProgress = true
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                        else -> if (index >= 0) {
                            try {
                                val buffer = codec.getOutputBuffer(index)

                                if (buffer != null && info.size > 0) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)

                                    val samples = decodeSamples(
                                        buffer.slice(),
                                        pcmEncoding
                                    )

                                    val bytes = ByteBuffer
                                        .allocate(samples.size * 2)
                                        .order(ByteOrder.LITTLE_ENDIAN)

                                    samples.forEach { bytes.putShort(it) }

                                    stream.write(bytes.array())
                                    totalBytes += bytes.capacity()

                                    require(totalBytes <= MAX_OUTPUT_BYTES) {
                                        "Audio is too large to convert safely."
                                    }

                                    madeProgress = true
                                }

                                outputDone =
                                    info.flags and
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                                if (outputDone) madeProgress = true
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                    }

                    if (madeProgress) {
                        lastProgressTime = System.nanoTime()
                    }
                }
            }

            require(totalBytes > 0L) {
                "No audio samples were decoded."
            }

            require(sourceRate > 0) {
                "Invalid audio sample rate."
            }

            require(sourceChannels in 1..2) {
                "Only mono and stereo audio are supported."
            }

            convertToTargetFormat(
                rawFile = rawFile,
                outputRaw = convertedFile,
                sourceRate = sourceRate,
                sourceChannels = sourceChannels
            )

            writeWav(output, convertedFile)

            require(output.exists() && output.length() > 44L) {
                "The converted audio file is empty."
            }

            return output
        } catch (e: Exception) {
            output.delete()

            throw IllegalStateException(
                "Could not import audio: ${e.message ?: "Unknown error"}",
                e
            )
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }

            try {
                codec?.release()
            } catch (_: Exception) {
            }

            try {
                extractor?.release()
            } catch (_: Exception) {
            }

            rawFile.delete()
            convertedFile.delete()
        }
    }

    private fun decodeSamples(
        buffer: ByteBuffer,
        encoding: Int
    ): ShortArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        return when (encoding) {
            PCM_16_BIT -> {
                val count = buffer.remaining() / 2
                ShortArray(count) { buffer.short }
            }

            PCM_8_BIT -> {
                val count = buffer.remaining()

                ShortArray(count) {
                    ((buffer.get().toInt() and 0xFF) - 128)
                        .let { it * 256 }
                        .toShort()
                }
            }

            PCM_FLOAT -> {
                val count = buffer.remaining() / 4

                ShortArray(count) {
                    val value = buffer.float.coerceIn(-1f, 1f)

                    (value * 32767f)
                        .roundToInt()
                        .coerceIn(-32768, 32767)
                        .toShort()
                }
            }

            else -> error(
                "Unsupported decoded PCM encoding: $encoding"
            )
        }
    }

private fun convertToTargetFormat(
    rawFile: File,
    outputRaw: File,
    sourceRate: Int,
    sourceChannels: Int
) {
    require(sourceRate in 8000..192000) {
        "Unsupported source sample rate."
    }

    require(sourceChannels in 1..2) {
        "Only mono and stereo audio are supported."
    }

    val bytesPerFrame = sourceChannels * 2
    val sourceFrames = rawFile.length() / bytesPerFrame

    require(sourceFrames > 0L) {
        "Audio contains no complete frames."
    }

    val targetFrames = (
        sourceFrames.toDouble() * TARGET_RATE / sourceRate
    ).roundToInt().coerceAtLeast(1)

    val targetBytes = targetFrames.toLong() * TARGET_CHANNELS * 2

    require(targetBytes <= MAX_OUTPUT_BYTES) {
        "Converted audio is too large."
    }

    RandomAccessFile(rawFile, "r").use { input ->
        BufferedOutputStream(
            FileOutputStream(outputRaw),
            64 * 1024
        ).use { output ->

            val frameBytes = ByteArray(bytesPerFrame)

            fun readFrame(): Pair<Short, Short> {
                input.readFully(frameBytes)

                val left = (
                    (frameBytes[0].toInt() and 0xFF) or
                    ((frameBytes[1].toInt() and 0xFF) shl 8)
                ).toShort()

                val right = if (sourceChannels == 1) {
                    left
                } else {
                    (
                        (frameBytes[2].toInt() and 0xFF) or
                        ((frameBytes[3].toInt() and 0xFF) shl 8)
                    ).toShort()
                }

                return left to right
            }

            var current = readFrame()
            var next = if (sourceFrames > 1L) {
                readFrame()
            } else {
                current
            }

            var currentIndex = 0L
            var nextIndex = if (sourceFrames > 1L) 1L else 0L

            val outputBuffer = ByteBuffer
                .allocate(64 * 1024)
                .order(ByteOrder.LITTLE_ENDIAN)

            fun flushBuffer() {
                if (outputBuffer.position() > 0) {
                    output.write(
                        outputBuffer.array(),
                        0,
                        outputBuffer.position()
                    )
                    outputBuffer.clear()
                }
            }

            for (frame in 0 until targetFrames) {
                val position =
                    frame.toDouble() * sourceRate / TARGET_RATE

                val leftIndex = floor(position)
                    .toLong()
                    .coerceAtMost(sourceFrames - 1L)

                while (currentIndex < leftIndex) {
                    current = next
                    currentIndex = nextIndex

                    if (currentIndex < sourceFrames - 1L) {
                        next = readFrame()
                        nextIndex = currentIndex + 1L
                    } else {
                        next = current
                        nextIndex = currentIndex
                    }
                }

                val fraction = (position - floor(position)).toFloat()

                val left = (
                    current.first +
                    (next.first - current.first) * fraction
                ).roundToInt().coerceIn(-32768, 32767)

                val right = (
                    current.second +
                    (next.second - current.second) * fraction
                ).roundToInt().coerceIn(-32768, 32767)

                if (outputBuffer.remaining() < 4) {
                    flushBuffer()
                }

                outputBuffer.putShort(left.toShort())
                outputBuffer.putShort(right.toShort())
            }

            flushBuffer()
        }
    }
}

    private fun writeWav(output: File, pcmFile: File) {
        val dataSize = pcmFile.length()

        require(dataSize > 0L && dataSize <= 0xFFFFFFFFL - 36L) {
            "Invalid WAV output size."
        }

        BufferedOutputStream(FileOutputStream(output)).use { stream ->
            val header = ByteBuffer.allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)

            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt((36L + dataSize).toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(TARGET_CHANNELS.toShort())
            header.putInt(TARGET_RATE)
            header.putInt(TARGET_RATE * TARGET_CHANNELS * 2)
            header.putShort((TARGET_CHANNELS * 2).toShort())
            header.putShort(TARGET_BITS.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize.toInt())

            stream.write(header.array())

            BufferedInputStream(
                FileInputStream(pcmFile),
                64 * 1024
            ).use { input ->
                input.copyTo(stream, 64 * 1024)
            }
        }
    }
}
