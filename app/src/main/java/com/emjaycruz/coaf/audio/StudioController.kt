package com.emjaycruz.coaf.audio

import android.content.Context
import com.emjaycruz.coaf.model.RecordingTake
import com.emjaycruz.coaf.model.StudioProject
import com.emjaycruz.coaf.model.StudioTrack
import com.emjaycruz.coaf.model.TrackType
import com.emjaycruz.coaf.model.ProjectStore
import java.io.File
import java.util.UUID

class StudioController(context: Context) {

private val appContext = context.applicationContext

private val recordingsDirectory = File(
    appContext.filesDir,
    "coaf_recordings"
).apply {
    mkdirs()
}

private val recorder = AudioRecorder(recordingsDirectory)
private val playbackEngine = AudioPlaybackEngine()
private val projectStore = ProjectStore(appContext)

@Volatile
var currentProject: StudioProject? = null
    private set

@Volatile
var activeRecordingTrackId: String? = null
    private set

fun createProject(name: String): StudioProject {
    require(name.isNotBlank()) {
        "Project name cannot be empty."
    }

    val project = StudioProject(
        id = UUID.randomUUID().toString(),
        name = name.trim()
    )

    projectStore.saveProject(project)
    currentProject = project

    return project
}

fun openProject(projectId: String): StudioProject {
    check(recorder.state == AudioRecorder.State.IDLE) {
        "Stop recording before opening another project."
    }

    check(!playbackEngine.isPlaying()) {
        "Stop playback before opening another project."
    }

    val project = requireNotNull(
        projectStore.loadProject(projectId)
    ) {
        "Project could not be found."
    }

    currentProject = project
    return project
}

fun getSavedProjects(): List<StudioProject> {
    return projectStore.listProjects()
}

fun addTrack(
    name: String,
    type: TrackType
): StudioTrack {
    require(name.isNotBlank()) {
        "Track name cannot be empty."
    }

    val project = requireNotNull(currentProject) {
        "Open or create a project first."
    }

    val track = StudioTrack(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        type = type
    )

    updateProject(
        project.copy(tracks = project.tracks + track)
    )

    return track
}

fun renameTrack(trackId: String, newName: String) {
    require(newName.isNotBlank()) {
        "Track name cannot be empty."
    }

    val project = requireNotNull(currentProject)

    val tracks = project.tracks.map { track ->
        if (track.id == trackId) {
            track.copy(name = newName.trim())
        } else {
            track
        }
    }

    updateProject(project.copy(tracks = tracks))
}

fun setMute(trackId: String, muted: Boolean) {
    updateTrack(trackId) {
        it.copy(isMuted = muted)
    }
}

fun setSolo(trackId: String, solo: Boolean) {
    updateTrack(trackId) {
        it.copy(isSolo = solo)
    }
}

fun setVolume(trackId: String, volume: Float) {
    updateTrack(trackId) {
        it.copy(volume = volume.coerceIn(0f, 1f))
    }
}

fun setPan(trackId: String, pan: Float) {
    updateTrack(trackId) {
        it.copy(pan = pan.coerceIn(-1f, 1f))
    }
}

fun startRecording(trackId: String): File {
    check(activeRecordingTrackId == null) {
        "A recording is already active."
    }

    val project = requireNotNull(currentProject) {
        "Open or create a project first."
    }

    require(project.tracks.any { it.id == trackId }) {
        "Recording track not found."
    }

    val file = recorder.start()
    activeRecordingTrackId = trackId

    return file
}

fun pauseRecording() {
    recorder.pause()
}

fun resumeRecording() {
    recorder.resume()
}

fun stopRecording(takeName: String = "Take"): RecordingTake {
    val trackId = requireNotNull(activeRecordingTrackId) {
        "No recording track is active."
    }

    val file = recorder.stop()

    val take = RecordingTake(
        id = UUID.randomUUID().toString(),
        name = takeName,
        audioPath = file.absolutePath
    )

    updateTrack(trackId) { track ->
        track.copy(recordings = track.recordings + take)
    }

    activeRecordingTrackId = null

    return take
}

fun playProject() {
    check(activeRecordingTrackId == null) {
        "Stop recording before playback."
    }

    val project = requireNotNull(currentProject) {
        "Open or create a project first."
    }

    val tracks = project.tracks.flatMap { track ->
        track.recordings.mapNotNull { take ->
            val file = File(take.audioPath)

            if (!file.isFile) {
                null
            } else {
                PlaybackTrack(
                    file = file,
                    muted = track.isMuted,
                    solo = track.isSolo,
                    volume = track.volume,
                    pan = track.pan
                )
            }
        }
    }

    playbackEngine.play(tracks)
}

fun stopPlayback() {
    playbackEngine.stop()
}

fun deleteProject(projectId: String): Boolean {
    check(currentProject?.id != projectId) {
        "Close the current project before deleting it."
    }

    return projectStore.deleteProject(projectId)
}

private fun updateTrack(
    trackId: String,
    transform: (StudioTrack) -> StudioTrack
) {
    val project = requireNotNull(currentProject) {
        "No project is open."
    }

    val found = project.tracks.any { it.id == trackId }

    require(found) {
        "Track not found."
    }

    val updatedTracks = project.tracks.map { track ->
        if (track.id == trackId) transform(track) else track
    }

    updateProject(project.copy(tracks = updatedTracks))
}

private fun updateProject(project: StudioProject) {
    val updated = project.copy(
        updatedAt = System.currentTimeMillis()
    )

    projectStore.saveProject(updated)
    currentProject = updated
}

}
