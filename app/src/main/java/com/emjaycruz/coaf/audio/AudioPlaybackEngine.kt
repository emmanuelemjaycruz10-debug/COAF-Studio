package com.emjaycruz.coaf.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.roundToInt

data class PlaybackTrack(
val file: File,
val muted: Boolean = false,
val solo: Boolean = false,
val volume: Float = 1f,
val pan: Float = 0f
)

class AudioPlaybackEngine {

companion object {
    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = 2
    private const val BYTES_PER_FRAME = 4
    private const val CHUNK_FRAMES = 1024
}

@Volatile
private var playing = false

@Volatile
private var audioTrack: AudioTrack? = null

@Volatile
private var worker: Thread? = null

@Volatile
var lastError: String? = null
    private set

@Synchronized
fun play(tracks: List<PlaybackTrack>) {
    check(!playing) { "Playback is already running." }
    require(tracks.isNotEmpty()) { "There are no tracks to play." }

    val audibleTracks = tracks.filter { !it.muted }
    val hasSolo = audibleTracks.any { it.solo }
    val selected = audibleTracks.filter {
        !hasSolo || it.solo
    }

    require(selected.isNotEmpty()) {
        "No audible tracks are selected."
    }

    val readers = selected.map { WaveReader(it) }

    try {
        readers.forEach { it.open() }

        val minimumBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        check(minimumBuffer > 0) {
            "This device does not support the playback format."
        }

        val output = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(
                maxOf(minimumBuffer * 2, CHUNK_FRAMES * BYTES_PER_FRAME * 2)
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        check(output.state == AudioTrack.STATE_INITIALIZED) {
            output.release()
            "Could not initialize audio playback."
        }

        audioTrack = output
        lastError = null
        playing = true

        worker = thread(name = "COAF-AudioPlayback") {
            val mixed = ShortArray(CHUNK_FRAMES * CHANNELS)

            try {
                output.play()

                while (playing) {
                    mixed.fill(0)

                    var anyFrames = false

                    for (reader in readers) {
                        val samples = reader.readFrames(CHUNK_FRAMES)
                        if (samples.isEmpty()) continue

                        anyFrames = true

                        val leftGain =
                            reader.leftGain
                        val rightGain =
                            reader.rightGain

                        val frameCount = samples.size / CHANNELS

                        for (frame in 0 until frameCount) {
                            val leftIndex = frame * CHANNELS
                            val rightIndex = leftIndex + 1

                            val left = (
                                samples[leftIndex] * leftGain
                            ).roundToInt()

                            val right = (
                                samples[rightIndex] * rightGain
                            ).roundToInt()

                            mixed[leftIndex] = (
                                mixed[leftIndex].toInt() + left
                            ).coerceIn(-32768, 32767).toShort()

                            mixed[rightIndex] = (
                                mixed[rightIndex].toInt() + right
                            ).coerceIn(-32768, 32767).toShort()
                        }
                    }

                    if (!anyFrames) break

                    var written = 0
                    val sampleCount = if (anyFrames) {
                        mixed.size
                    } else {
                        0
                    }

                    while (written < sampleCount && playing) {
                        val count = output.write(
                            mixed,
                            written,
                            sampleCount - written
                        )

                        if (count < 0) {
                            throw IllegalStateException(
                                "Audio output failed: $count"
                            )
                        }

                        if (count == 0) break
                        written += count
                    }
                }
            } catch (error: Exception) {
                lastError = error.message
            } finally {
                playing = false

                try {
                    output.pause()
                    output.flush()
                    output.stop()
                } catch (_: Exception) {
                }

                output.release()
                readers.forEach { it.close() }

                synchronized(this) {
                    if (audioTrack === output) {
                        audioTrack = null
                        worker = null
                    }
                }
            }
        }
    } catch (error: Exception) {
        readers.forEach { it.close() }
        throw error
    }
}

@Synchronized
fun stop() {
    playing = false
    val currentWorker = worker

    if (Thread.currentThread() != currentWorker) {
        currentWorker?.join(2000)
    }

    if (currentWorker?.isAlive != true) {
        worker = null
        audioTrack = null
    }
}

fun isPlaying(): Boolean = playing

private class WaveReader(
    private val track: PlaybackTrack
) {
    private var input: RandomAccessFile? = null
    private var dataStart = 0L
    private var dataSize = 0L
    private var consumed = 0L
    private var channels = 0
    private var sampleRate = 0
    private var bits = 0
    private var format = 0

    val leftGain: Float
        get() {
            val volume = track.volume.coerceIn(0f, 1f)
            val pan = track.pan.coerceIn(-1f, 1f)
            return volume * if (pan > 0f) 1f - pan else 1f
        }

    val rightGain: Float
        get() {
            val volume = track.volume.coerceIn(0f, 1f)
            val pan = track.pan.coerceIn(-1f, 1f)
            return volume * if (pan < 0f) 1f + pan else 1f
        }

    fun open() {
        val file = RandomAccessFile(track.file, "r")
        input = file

        require(readText(file, 4) == "RIFF") {
            "Not a supported WAV file: ${track.file.name}"
        }

        file.readIntLE()
        require(readText(file, 4) == "WAVE") {
            "Invalid WAV header."
        }

        var foundFormat = false
        var foundData = false

        while (file.filePointer + 8 <= file.length()) {
            val chunkId = readText(file, 4)
            val chunkSize = file.readUnsignedIntLE()
            val chunkStart = file.filePointer

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16) {
                        "Invalid WAV format chunk."
                    }

                    format = file.readUnsignedShortLE()
                    channels = file.readUnsignedShortLE()
                    sampleRate = file.readIntLE()
                    file.readIntLE()
                    file.readUnsignedShortLE()
                    bits = file.readUnsignedShortLE()
                    foundFormat = true
                }

                "data" -> {
                    dataStart = chunkStart
                    dataSize = chunkSize.coerceAtMost(
                        file.length() - chunkStart
                    )
                    foundData = true
                }
            }

            val nextChunk = chunkStart + chunkSize +
                (chunkSize % 2)

            if (nextChunk > file.length()) break
            file.seek(nextChunk)

            if (foundFormat && foundData) break
        }

        require(foundFormat && foundData) {
            "WAV is missing format or audio data."
        }

        require(
            format == 1 &&
                bits == 16 &&
                (channels == 1 || channels == 2) &&
                sampleRate == SAMPLE_RATE
        ) {
            "Only 44.1 kHz, 16-bit PCM mono/stereo WAV is supported."
        }

        file.seek(dataStart)
    }

    fun readFrames(maxFrames: Int): ShortArray {
        val file = input ?: return ShortArray(0)
        val bytesPerFrame = channels * 2
        val remaining = dataSize - consumed

        if (remaining < bytesPerFrame) return ShortArray(0)

        val frames = minOf(
            maxFrames.toLong(),
            remaining / bytesPerFrame
        ).toInt()

        val bytes = ByteArray(frames * bytesPerFrame)
        val count = file.read(bytes)

        if (count <= 0) return ShortArray(0)

        val actualFrames = count / bytesPerFrame
        val result = ShortArray(actualFrames * 2)

        for (frame in 0 until actualFrames) {
            val offset = frame * bytesPerFrame

            val first = (
                (bytes[offset].toInt() and 0xFF) or
                    (bytes[offset + 1].toInt() shl 8)
                ).toShort()

            if (channels == 1) {
                result[frame * 2] = first
                result[frame * 2 + 1] = first
            } else {
                val second = (
                    (bytes[offset + 2].toInt() and 0xFF) or
                        (bytes[offset + 3].toInt() shl 8)
                    ).toShort()

                result[frame * 2] = first
                result[frame * 2 + 1] = second
            }
        }

        consumed += actualFrames.toLong() * bytesPerFrame
        return result
    }

    fun close() {
        try {
            input?.close()
        } catch (_: Exception) {
        }
        input = null
    }

    private fun readText(
        file: RandomAccessFile,
        length: Int
    ): String {
        val bytes = ByteArray(length)
        file.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val a = readUnsignedByte()
        val b = readUnsignedByte()
        val c = readUnsignedByte()
        val d = readUnsignedByte()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }

    private fun RandomAccessFile.readUnsignedIntLE(): Long {
        return readIntLE().toLong() and 0xFFFFFFFFL
    }

    private fun RandomAccessFile.readUnsignedShortLE(): Int {
        val a = readUnsignedByte()
        val b = readUnsignedByte()
        return a or (b shl 8)
    }
}

}
