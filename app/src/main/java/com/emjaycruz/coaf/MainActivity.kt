package com.emjaycruz.coaf

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.emjaycruz.coaf.audio.AudioRecorder
import com.emjaycruz.coaf.audio.StudioController
import com.emjaycruz.coaf.model.StudioProject
import com.emjaycruz.coaf.model.StudioTrack
import com.emjaycruz.coaf.model.TrackType

private val Background = Color(0xFF101014)
private val Panel = Color(0xFF202027)
private val Purple = Color(0xFF9B5CFF)
private val Green = Color(0xFF55E6A5)
private val White = Color(0xFFF7F4FF)

class MainActivity : ComponentActivity() {

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    window.statusBarColor = android.graphics.Color.rgb(16, 16, 20)
    window.navigationBarColor = android.graphics.Color.rgb(16, 16, 20)

    setContent {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Purple,
                secondary = Green,
                background = Background,
                surface = Panel
            )
        ) {
            CoafApp()
        }
    }
}

}

@Composable
private fun CoafApp() {
val context = androidx.compose.ui.platform.LocalContext.current
val controller = remember {
StudioController(context.applicationContext)
}

var projects by remember { mutableStateOf(emptyList<StudioProject>()) }
var currentProject by remember { mutableStateOf<StudioProject?>(null) }
var refresh by remember { mutableIntStateOf(0) }

var showCreateDialog by remember { mutableStateOf(false) }
var newProjectName by remember { mutableStateOf("") }

var recordingTrackId by remember { mutableStateOf<String?>(null) }
var recordingPaused by remember { mutableStateOf(false) }
var playbackActive by remember { mutableStateOf(false) }

var pendingTrackId by remember { mutableStateOf<String?>(null) }

fun reload() {
    projects = controller.getSavedProjects()
    currentProject = controller.currentProject
    refresh++
}

fun reportError(error: Exception) {
    Toast.makeText(
        context,
        error.message ?: "Something went wrong",
        Toast.LENGTH_LONG
    ).show()
}

fun startRecording(trackId: String) {
    try {
        controller.startRecording(trackId)
        recordingTrackId = trackId
        recordingPaused = false
        reload()
        Toast.makeText(
            context,
            "Recording started",
            Toast.LENGTH_SHORT
        ).show()
    } catch (error: Exception) {
        reportError(error)
    }
}

val microphonePermission = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
) { granted ->
    val trackId = pendingTrackId
    pendingTrackId = null

    if (granted && trackId != null) {
        startRecording(trackId)
    } else {
        Toast.makeText(
            context,
            "Microphone permission is required to record.",
            Toast.LENGTH_LONG
        ).show()
    }
}

fun requestRecording(trackId: String) {
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    if (granted) {
        startRecording(trackId)
    } else {
        pendingTrackId = trackId
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

Surface(
    modifier = Modifier.fillMaxSize(),
    color = Background
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "COAF STUDIO",
            color = Purple,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black
        )

        Text(
            text = "KING OF ALL FLAVORS",
            color = Green,
            fontSize = 11.sp,
            letterSpacing = 3.sp
        )

        Spacer(Modifier.height(18.dp))

        if (currentProject == null) {
            Text(
                text = "MY PROJECTS",
                color = White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    newProjectName = ""
                    showCreateDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Create New Project")
            }

            Spacer(Modifier.height(12.dp))

            if (projects.isEmpty()) {
                Text(
                    text = "Your music projects will appear here.",
                    color = Color.LightGray
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Panel,
                                    RoundedCornerShape(14.dp)
                                )
                                .padding(14.dp)
                        ) {
                            Text(
                                project.name,
                                color = White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )

                            Text(
                                "${project.tracks.size} tracks",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )

                            Spacer(Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    try {
                                        currentProject =
                                            controller.openProject(project.id)
                                        reload()
                                    } catch (error: Exception) {
                                        reportError(error)
                                    }
                                }
                            ) {
                                Text("Open Studio")
                            }
                        }
                    }
                }
            }
        } else {
            val project = currentProject!!

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        project.name,
                        color = White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "MULTITRACK STUDIO",
                        color = Green,
                        fontSize = 11.sp
                    )
                }

                TextButton(
                    onClick = {
                        if (recordingTrackId == null && !playbackActive) {
                            currentProject = null
                            reload()
                        } else {
                            Toast.makeText(
                                context,
                                "Stop recording or playback first.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text("Projects")
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            controller.addTrack(
                                "Vocal ${project.tracks.count { it.type == TrackType.VOCAL } + 1}",
                                TrackType.VOCAL
                            )
                            reload()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ Vocal")
                }

                Button(
                    onClick = {
                        try {
                            controller.addTrack(
                                "Voice Over",
                                TrackType.VOICE_OVER
                            )
                            reload()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ Voice-over")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            controller.addTrack(
                                "Backing Vocal",
                                TrackType.BACKING_VOCAL
                            )
                            reload()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ Backing")
                }

                Button(
                    onClick = {
                        try {
                            controller.addTrack(
                                "Ad-lib",
                                TrackType.AD_LIB
                            )
                            reload()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ Ad-lib")
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            controller.playProject()
                            playbackActive = true
                            Toast.makeText(
                                context,
                                "Playback started",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },
                    enabled = !playbackActive && recordingTrackId == null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("▶ Play")
                }

                Button(
                    onClick = {
                        try {
                            controller.stopPlayback()
                            playbackActive = false
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },
                    enabled = playbackActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("■ Stop")
                }
            }

            if (recordingTrackId != null) {
                Spacer(Modifier.height(8.dp))

                Text(
                    if (recordingPaused) "RECORDING PAUSED" else "● RECORDING",
                    color = if (recordingPaused) Green else Color.Red,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            try {
                                if (recordingPaused) {
                                    controller.resumeRecording()
                                } else {
                                    controller.pauseRecording()
                                }
                                recordingPaused = !recordingPaused
                            } catch (error: Exception) {
                                reportError(error)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (recordingPaused) "Resume" else "Pause")
                    }

                    Button(
                        onClick = {
                            try {
                                val take = controller.stopRecording()
                                recordingTrackId = null
                                recordingPaused = false
                                reload()

                                Toast.makeText(
                                    context,
                                    "Saved ${take.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (error: Exception) {
                                reportError(error)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop & Save Take")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "TRACKS  •  ${project.tracks.size}",
                color = Green,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            if (project.tracks.isEmpty()) {
                Text(
                    "Add a track to start recording.",
                    color = Color.LightGray
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(project.tracks, key = { it.id }) { track ->
                        TrackCard(
                            track = track,
                            isRecording = recordingTrackId == track.id,
                            recordingExists = recordingTrackId != null,
                            playbackActive = playbackActive,
                            onRecord = {
                                requestRecording(track.id)
                            },
                            onMute = {
                                try {
                                    controller.setMute(track.id, !track.isMuted)
                                    reload()
                                } catch (error: Exception) {
                                    reportError(error)
                                }
                            },
                            onSolo = {
                                try {
                                    controller.setSolo(track.id, !track.isSolo)
                                    reload()
                                } catch (error: Exception) {
                                    reportError(error)
                                }
                            },
                            onVolume = { value ->
                                controller.setVolume(track.id, value)
                                reload()
                            }
                        )
                    }
                }
            }

            Text(
                "Built for MJ Cruz",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

if (showCreateDialog) {
    AlertDialog(
        onDismissRequest = { showCreateDialog = false },
        title = { Text("New Project") },
        text = {
            OutlinedTextField(
                value = newProjectName,
                onValueChange = { newProjectName = it },
                label = { Text("Project name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newProjectName.isNotBlank()) {
                        try {
                            currentProject = controller.createProject(
                                newProjectName.trim()
                            )
                            showCreateDialog = false
                            reload()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    }
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { showCreateDialog = false }
            ) {
                Text("Cancel")
            }
        }
    )
}

// Keep the local state tied to explicit reloads.
@Suppress("UNUSED_EXPRESSION")
refresh

}

@Composable
private fun TrackCard(
track: StudioTrack,
isRecording: Boolean,
recordingExists: Boolean,
playbackActive: Boolean,
onRecord: () -> Unit,
onMute: () -> Unit,
onSolo: () -> Unit,
onVolume: (Float) -> Unit
) {
var volume by remember(track.id, track.volume) {
mutableFloatStateOf(track.volume)
}

Column(
    modifier = Modifier
        .fillMaxWidth()
        .background(Panel, RoundedCornerShape(14.dp))
        .padding(12.dp)
) {
    Text(
        track.name,
        color = White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
    )

    Text(
        "${track.type.name.replace('_', ' ')} • ${track.recordings.size} takes",
        color = Color.LightGray,
        fontSize = 12.sp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = track.isMuted,
            onClick = onMute,
            label = { Text(if (track.isMuted) "Muted" else "Mute") }
        )

        FilterChip(
            selected = track.isSolo,
            onClick = onSolo,
            label = { Text(if (track.isSolo) "Solo ON" else "Solo")
