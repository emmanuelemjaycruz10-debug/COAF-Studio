package com.emjaycruz.coaf.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

class AudioDecoder(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TARGET_RATE = 44100
        private const val TARGET_CHANNELS = 2
        private const val TARGET_BITS = 16
        private const val MAX_OUTPUT_BYTES = 1_500_000_000L
    }

    fun decodeToWav(source: Uri, output: File): File {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        val rawFile = File(output.parentFile, "${output.name}.raw")
        val convertedFile = File(output.parentFile, "${output.name}.converted")

        try {
            output.parentFile?.mkdirs()
            require(output.parentFile?.exists() == true) {
                "Cannot create audio output directory."
            }

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
                "No audio track was found."
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

            var pcmEncoding = 2 // PCM 16-bit is the usual decoder output.

            var inputDone = false
            var outputDone = false
            var totalBytes = 0L

            FileOutputStream(rawFile).use { stream ->
                val info = MediaCodec.BufferInfo()

                while (!outputDone) {

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

                            pcmEncoding = format.getInteger(
                                MediaFormat.KEY_PCM_ENCODING,
                                2
                            )
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // Wait for more decoded audio.
                        }

                        else -> if (index >= 0) {
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
                            }

                            outputDone =
                                info.flags and
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                            codec.releaseOutputBuffer(index, false)
                        }
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

            return output
        } catch (e: Exception) {
            output.delete()

            throw IllegalStateException(
                "Could not decode audio: ${e.message ?: "Unknown error"}",
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
                extractor.release()
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
            2 -> {
                val count = buffer.remaining() / 2
                ShortArray(count) { buffer.short }
            }

            3 -> {
                val count = buffer.remaining()
                ShortArray(count) {
                    ((buffer.get().toInt() and 0xFF) - 128)
                        .let { it * 256 }
                        .toShort()
                }
            }

            4 -> {
                val count = buffer.remaining() / 4

                ShortArray(count) {
                    val value = buffer.float.coerceIn(-1f, 1f)
                    (value * 32767f).roundToInt()
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
            FileOutputStream(outputRaw).use { output ->
                val sourceBuffer = ByteArray(bytesPerFrame)

                fun readSample(frame: Long, channel: Int): Short {
                    val safeFrame = frame.coerceIn(0L, sourceFrames - 1L)

                    input.seek(safeFrame * bytesPerFrame)

                    input.readFully(sourceBuffer)

                    val actualChannel =
                        if (sourceChannels == 1) 0 else channel

                    val offset = actualChannel * 2

                    return ByteBuffer.wrap(sourceBuffer, offset, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short
                }

                val outputBuffer = ByteBuffer
                    .allocate(8192)
                    .order(ByteOrder.LITTLE_ENDIAN)

                for (frame in 0 until targetFrames) {
                    val sourcePosition =
                        frame.toDouble() * sourceRate / TARGET_RATE

                    val leftFrame = floor(sourcePosition).toLong()
                    val fraction = (sourcePosition - leftFrame).toFloat()

                    val nextFrame = (leftFrame + 1)
                        .coerceAtMost(sourceFrames - 1)

                    for (channel in 0 until TARGET_CHANNELS) {
                        val first = readSample(
                            leftFrame,
                            channel
                        ).toFloat()

                        val second = readSample(
                            nextFrame,
                            channel
                        ).toFloat()

                        val sample = (
                            first + (second - first) * fraction
                        ).roundToInt().coerceIn(-32768, 32767)

                        if (outputBuffer.remaining() < 2) {
                            output.write(outputBuffer.array(), 0, outputBuffer.position())
                            outputBuffer.clear()
                        }

                        outputBuffer.putShort(sample.toShort())
                    }
                }

                if (outputBuffer.position() > 0) {
                    output.write(
                        outputBuffer.array(),
                        0,
                        outputBuffer.position()
                    )
                }
            }
        }
    }

    private fun writeWav(output: File, pcmFile: File) {
        val dataSize = pcmFile.length()

        require(dataSize > 0L && dataSize <= 0xFFFFFFFFL - 36L) {
            "Invalid WAV output size."
        }

        FileOutputStream(output).use { stream ->
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

            pcmFile.inputStream().use { input ->
                input.copyTo(stream)
            }
        }
    }
}
