package com.emjaycruz.coaf.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

class AudioDecoder(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Decodes a supported audio URI into a PCM WAV file.
     *
     * Output format:
     * - 44,100 Hz
     * - Mono
     * - 16-bit PCM
     *
     * Returns the WAV file, or throws an exception if decoding fails.
     */
    fun decodeToWav(source: Uri, output: File): File {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
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
                "No supported audio track was found."
            }

            extractor.selectTrack(selectedTrack)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Audio format is unknown.")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            output.parentFile?.mkdirs()

            val temporaryPcm = File(output.parentFile, "${output.name}.pcm")
            var totalBytes = 0L
            var outputSampleRate = 44100
            var outputChannels = 1

            FileOutputStream(temporaryPcm).use { stream ->
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)

                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                                ?: error("Could not access decoder input buffer.")

                            val sampleSize = extractor.readSampleData(inputBuffer, 0)

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

                    when (val outputIndex =
                        codec.dequeueOutputBuffer(bufferInfo, 10_000)) {

                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            outputSampleRate = format.getInteger(
                                MediaFormat.KEY_SAMPLE_RATE
                            )
                            outputChannels = format.getInteger(
                                MediaFormat.KEY_CHANNEL_COUNT
                            )
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // Wait for the next decoder output.
                        }

                        else -> if (outputIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)

                            if (buffer != null && bufferInfo.size > 0) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)

                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                stream.write(bytes)
                                totalBytes += bytes.size
                            }

                            outputDone =
                                bufferInfo.flags and
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            require(totalBytes > 0L) {
                "The decoder returned no audio samples."
            }

            /*
             * This initial decoder accepts PCM output only when it is already
             * mono, 16-bit, 44.1 kHz. Resampling and channel conversion will
             * be handled in the next integration step.
             */
            require(outputSampleRate == 44100 && outputChannels == 1) {
                "Decoded audio is ${outputSampleRate}Hz, ${outputChannels} channel(s). " +
                    "Conversion to 44.1kHz mono is required before playback."
            }

            writeWavHeader(
                output = output,
                pcmData = temporaryPcm,
                sampleRate = outputSampleRate,
                channels = outputChannels
            )

            temporaryPcm.delete()
            return output
        } catch (e: Exception) {
            output.delete()
            throw IllegalStateException(
                "Audio decoding failed: ${e.message ?: "Unknown error"}",
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

            extractor.release()
        }
    }

    private fun writeWavHeader(
        output: File,
        pcmData: File,
        sampleRate: Int,
        channels: Int
    ) {
        val dataSize = pcmData.length()
        require(dataSize <= 0xFFFFFFFFL - 36L) {
            "Audio file is too large for a standard WAV file."
        }

        FileOutputStream(output).use { fileStream ->
            val channel = fileStream.channel
            val header = ByteBuffer.allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)

            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt((36L + dataSize).toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * channels * 2)
            header.putShort((channels * 2).toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize.toInt())
            header.flip()

            channel.write(header)

            FileInputStreamCompat.copy(pcmData, fileStream)
        }
    }

    private object FileInputStreamCompat {
        fun copy(source: File, destination: FileOutputStream) {
            source.inputStream().use { input ->
                input.copyTo(destination)
            }
        }
    }
}
