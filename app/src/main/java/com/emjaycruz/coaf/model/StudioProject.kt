package com.emjaycruz.coaf.model

data class StudioProject(
val id: String,
val name: String,
val tracks: List<StudioTrack> = emptyList(),
val createdAt: Long = System.currentTimeMillis(),
val updatedAt: Long = System.currentTimeMillis()
)

data class StudioTrack(
val id: String,
val name: String,
val type: TrackType,
val recordings: List<RecordingTake> = emptyList(),
val isMuted: Boolean = false,
val isSolo: Boolean = false,
val volume: Float = 1.0f,
val pan: Float = 0.0f
)

data class RecordingTake(
val id: String,
val name: String,
val audioPath: String,
val durationMs: Long = 0L,
val createdAt: Long = System.currentTimeMillis()
)

enum class TrackType {
INSTRUMENTAL,
VOCAL,
BACKING_VOCAL,
AD_LIB,
VOICE_OVER,
OTHER
}
