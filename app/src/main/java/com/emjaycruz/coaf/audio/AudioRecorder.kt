package com.emjaycruz.coaf.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioRecorder(
    private val recordingsDirectory: File
) {
    companion object {
        const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16

        private const val CHANNEL_CONFIG =
            AudioFormat.CHANNEL_IN_MONO

        private const val ENCODING =
            AudioFormat.ENCODING_PCM_16BIT
    }

    enum class State {
        IDLE,
        RECORDING,
        PAUSED,
        STOPPING
    }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var worker: Thread? = null

    private val stopRequested = AtomicBoolean(false)

    private var outputFile: File? = null
    private var output: RandomAccessFile? = null
    private var audioBytesWritten = 0L

    @Volatile
    private var pauseRequested = false

    @Synchronized
    fun start(): File {
        check(state == State.IDLE) {
            "Recorder is already active."
        }

        require(
            recordingsDirectory.exists() ||
                recordingsDirectory.mkdirs()
        ) {
            "Could not create recordings directory."
        }

        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            ENCODING
        )

        check(minimumBuffer > 0) {
            "This device does not support the requested audio format."
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            ENCODING,
            maxOf(minimumBuffer * 2, 8192)
        )

        check(
            audioRecord.state == AudioRecord.STATE_INITIALIZED
        ) {
            audioRecord.release()
            "Could not initialize microphone recording."
        }

        val file = File(
            recordingsDirectory,
            "take_${UUID.randomUUID()}.wav"
        )

        try {
            val randomFile = RandomAccessFile(file, "rw")
            randomFile.setLength(0)
            writeWavHeader(randomFile, 0)

            outputFile = file
            output = randomFile
            audioBytesWritten = 0L
            lastError = null
            pauseRequested = false
            stopRequested.set(false)
            recorder = audioRecord
            state = State.RECORDING

            audioRecord.startRecording()

            worker = thread(
                name = "COAF-AudioRecorder",
                start = true
            ) {
                captureAudio(audioRecord)
            }

            return file
        } catch (error: Exception) {
            state = State.IDLE

            try {
                audioRecord.release()
            } catch (_: Exception) {
            }

            try {
                output?.close()
            } catch (_: Exception) {
            }

            output = null
            recorder = null
            outputFile = null

            file.delete()

            throw IllegalStateException(
                "Could not start recording: ${error.message}",
                error
            )
        }
    }

    @Synchronized
    fun pause() {
        check(state == State.RECORDING) {
            "Recording is not active."
        }

        pauseRequested = true
        state = State.PAUSED
    }

    @Synchronized
    fun resume() {
        check(state == State.PAUSED) {
            "Recording is not paused."
        }

        pauseRequested = false
        state = State.RECORDING
    }

    @Synchronized
    fun stop(): File {
        check(
            state == State.RECORDING ||
                state == State.PAUSED
        ) {
            "There is no active recording."
        }

        state = State.STOPPING
        stopRequested.set(true)

        val captureThread = worker

        if (Thread.currentThread() != captureThread) {
            captureThread?.join()
        }

        val file = outputFile
            ?: throw IllegalStateException("Recording file is missing.")

        try {
            output?.let { randomFile ->
                writeWavHeader(randomFile, audioBytesWritten)
                randomFile.fd.sync()
                randomFile.close()
            }
        } finally {
            output = null

            try {
                recorder?.release()
            } catch (_: Exception) {
            }

            recorder = null
            worker = null
            outputFile = null
            pauseRequested = false
            state = State.IDLE
        }

        if (audioBytesWritten == 0L) {
            file.delete()
            throw IllegalStateException(
                "No audio was captured. Check microphone permission."
            )
        }

        return file
    }

    private fun captureAudio(audioRecord: AudioRecord) {
        val buffer = ByteArray(4096)

        try {
            while (!stopRequested.get()) {
                if (pauseRequested) {
                    Thread.sleep(10)
                    continue
                }

                val count = audioRecord.read(
                    buffer,
                    0,
                    buffer.size
                )

                if (count > 0 && !pauseRequested) {
                    synchronized(this) {
                        if (!pauseRequested && !stopRequested.get()) {
                            output?.write(buffer, 0, count)
                            audioBytesWritten += count
                        }
                    }
                } else if (count < 0) {
                    throw IllegalStateException(
                        "Microphone read failed: $count"
                    )
                }
            }
        } catch (error: Exception) {
            lastError = error.message
            stopRequested.set(true)
        }
    }

    private fun writeWavHeader(
        file: RandomAccessFile,
        audioDataSize: Long
    ) {
        val safeSize = audioDataSize.coerceAtMost(
            0xFFFFFFFFL - 36L
        )

        val byteRate =
            SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

        file.seek(0)

        file.writeBytes("RIFF")
        writeIntLE(file, (safeSize + 36).toInt())
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        writeIntLE(file, 16)
        writeShortLE(file, 1)
        writeShortLE(file, CHANNELS)
        writeIntLE(file, SAMPLE_RATE)
        writeIntLE(file, byteRate)
        writeShortLE(file, CHANNELS * BITS_PER_SAMPLE / 8)
        writeShortLE(file, BITS_PER_SAMPLE)
        file.writeBytes("data")
        writeIntLE(file, safeSize.toInt())
    }

    private fun writeIntLE(
        file: RandomAccessFile,
        value: Int
    ) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
        file.write((value shr 16) and 0xFF)
        file.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(
        file: RandomAccessFile,
        value: Int
    ) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
    }
}
