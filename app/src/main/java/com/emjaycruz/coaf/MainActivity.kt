package com.emjaycruz.coaf

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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

        window.statusBarColor =
            android.graphics.Color.rgb(16, 16, 20)

        window.navigationBarColor =
            android.graphics.Color.rgb(16, 16, 20)

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

    var projects by remember {
        mutableStateOf(emptyList<StudioProject>())
    }

    var currentProject by remember {
        mutableStateOf<StudioProject?>(null)
    }

    var showCreateDialog by remember {
        mutableStateOf(false)
    }

    var newProjectName by remember {
        mutableStateOf("")
    }

    var recordingTrackId by remember {
        mutableStateOf<String?>(null)
    }

    var recordingPaused by remember {
        mutableStateOf(false)
    }

    var playbackActive by remember {
        mutableStateOf(false)
    }

    var pendingTrackId by remember {
        mutableStateOf<String?>(null)
    }

    var pendingImportTrackId by remember {
        mutableStateOf<String?>(null)
    }

    var refresh by remember {
        mutableIntStateOf(0)
    }

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

    val microphonePermission =
        rememberLauncherForActivityResult(
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

    val audioPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            val trackId = pendingImportTrackId
            pendingImportTrackId = null

            if (uri != null && trackId != null) {
                try {
                    val importedTake = controller.importAudio(
                        trackId,
                        uri
                    )

                    reload()

                    Toast.makeText(
                        context,
                        "Imported ${importedTake.name}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (error: Exception) {
                    reportError(error)
                }
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

            microphonePermission.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    fun requestAudioImport(trackId: String) {
        if (recordingTrackId != null || playbackActive) {
            Toast.makeText(
                context,
                "Stop recording or playback before importing audio.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        pendingImportTrackId = trackId

        audioPicker.launch(
            arrayOf("audio/*")
        )
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
                        modifier = Modifier.weight(1f),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            projects,
                            key = { it.id }
                        ) { project ->
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
                                            controller.openProject(
                                                project.id
                                            )
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
                StudioScreen(
                    project = currentProject!!,
                    controller = controller,
                    recordingTrackId = recordingTrackId,
                    recordingPaused = recordingPaused,
                    playbackActive = playbackActive,

                    onBack = {
                        if (
                            recordingTrackId == null &&
                            !playbackActive
                        ) {
                            currentProject = null
                            reload()
                        } else {
                            Toast.makeText(
                                context,
                                "Stop recording or playback first.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },

                    onAddTrack = { name, type ->
                        try {
                            controller.addTrack(name, type)
                            reload()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },

                    onImportAudio = { trackId ->
                        requestAudioImport(trackId)
                    },

                    onRecord = { trackId ->
                        requestRecording(trackId)
                    },

                    onPauseResume = {
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

                    onStopRecording = {
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

                    onPlay = {
                        try {
                            controller.playProject()
                            playbackActive = true
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },

                    onStopPlayback = {
                        try {
                            controller.stopPlayback()
                            playbackActive = false
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },

                    onMute = { track ->
                        try {
                            controller.setMute(
                                track.id,
                                !track.isMuted
                            )
                            reload()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },

                    onSolo = { track ->
                        try {
                            controller.setSolo(
                                track.id,
                                !track.isSolo
                            )
                            reload()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    },

                    onVolume = { track, value ->
                        try {
                            controller.setVolume(
                                track.id,
                                value
                            )
                            reload()
                        } catch (error: Exception) {
                            reportError(error)
                        }
                    }
                )
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
            },
            title = {
                Text("New Project")
            },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = {
                        newProjectName = it
                    },
                    label = {
                        Text("Project name")
                    },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newProjectName.isNotBlank()) {
                            try {
                                controller.createProject(
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
                    onClick = {
                        showCreateDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    @Suppress("UNUSED_EXPRESSION")
    refresh
}
@Composable
private fun StudioScreen(
    project: StudioProject,
    controller: StudioController,
    recordingTrackId: String?,
    recordingPaused: Boolean,
    playbackActive: Boolean,
    onBack: () -> Unit,
    onAddTrack: (String, TrackType) -> Unit,
    onImportAudio: (String) -> Unit,
    onRecord: (String) -> Unit,
    onPauseResume: () -> Unit,
    onStopRecording: () -> Unit,
    onPlay: () -> Unit,
    onStopPlayback: () -> Unit,
    onMute: (StudioTrack) -> Unit,
    onSolo: (StudioTrack) -> Unit,
    onVolume: (StudioTrack, Float) -> Unit
) {
    var selectedTrackType by remember {
        mutableStateOf(TrackType.VOCAL)
    }

    var customTrackName by remember {
        mutableStateOf("")
    }

    var menuExpanded by remember {
        mutableStateOf(false)
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Projects")
            }

            Spacer(Modifier.width(8.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    project.name,
                    color = White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "${project.tracks.size} tracks",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "TRACK CONTROLS",
            color = Green,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onPlay,
                enabled = !playbackActive &&
                    recordingTrackId == null,
                modifier = Modifier.weight(1f)
            ) {
                Text("▶ Play")
            }

            Button(
                onClick = onStopPlayback,
                enabled = playbackActive,
                modifier = Modifier.weight(1f)
            ) {
                Text("■ Stop")
            }
        }

        if (recordingTrackId != null) {
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Panel,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (recordingPaused) {
                            "Recording paused"
                        } else {
                            "● Recording"
                        },
                        color = if (recordingPaused) {
                            Color.Yellow
                        } else {
                            Green
                        },
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Stop to save this take",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                }

                Button(onClick = onPauseResume) {
                    Text(
                        if (recordingPaused) "Resume"
                        else "Pause"
                    )
                }

                Button(onClick = onStopRecording) {
                    Text("Save")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "ADD A TRACK",
            color = Green,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Box {
            OutlinedButton(
                onClick = {
                    menuExpanded = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Track type: ${selectedTrackType.name}")
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = {
                    menuExpanded = false
                }
            ) {
                TrackType.values().forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Text(type.name.replace("_", " "))
                        },
                        onClick = {
                            selectedTrackType = type
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = customTrackName,
            onValueChange = {
                customTrackName = it
            },
            label = {
                Text("Track name")
            },
            placeholder = {
                Text("e.g. Main vocals")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val name = customTrackName.trim()
                    .ifBlank {
                        selectedTrackType.name
                            .replace("_", " ")
                    }

                onAddTrack(name, selectedTrackType)
                customTrackName = ""
            },
            enabled = recordingTrackId == null &&
                !playbackActive,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("+ Add Track")
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "YOUR TRACKS",
            color = Green,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        if (project.tracks.isEmpty()) {
            Text(
                "No tracks yet. Add a track to start recording.",
                color = Color.LightGray,
                fontSize = 13.sp
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = project.tracks,
                    key = { it.id }
                ) { track ->
                    TrackCard(
                        track = track,
                        recording = recordingTrackId == track.id,
                        recordingPaused = recordingPaused,
                        canRecord = recordingTrackId == null &&
                            !playbackActive,
                        onRecord = {
                            onRecord(track.id)
                        },
                        onImportAudio = {
                            onImportAudio(track.id)
                        },
                        onMute = {
                            onMute(track)
                        },
                        onSolo = {
                            onSolo(track)
                        },
                        onVolume = { value ->
                            onVolume(track, value)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackCard(
    track: StudioTrack,
    recording: Boolean,
    recordingPaused: Boolean,
    canRecord: Boolean,
    onRecord: () -> Unit,
    onImportAudio: () -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onVolume: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Panel,
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    track.name,
                    color = White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    track.type.name.replace("_", " "),
                    color = Color.LightGray,
                    fontSize = 11.sp
                )

                Text(
                    "${track.recordings.size} takes",
                    color = Green,
                    fontSize = 12.sp
                )
            }

            Text(
                if (recording) {
                    if (recordingPaused) "PAUSED" else "REC"
                } else {
                    ""
                },
                color = if (recordingPaused) {
                    Color.Yellow
                } else {
                    Green
                },
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onImportAudio,
            enabled = canRecord && !recording,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("＋ Import Audio / Beat")
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onMute,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (track.isMuted) "Unmute" else "Mute"
                )
            }

            OutlinedButton(
                onClick = onSolo,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (track.isSolo) "Unsolo" else "Solo"
                )
            }

            Button(
                onClick = onRecord,
                enabled = canRecord && !recording,
                modifier = Modifier.weight(1f)
            ) {
                Text("● Record")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Volume ${(track.volume * 100).toInt()}%",
            color = Color.LightGray,
            fontSize = 12.sp
        )

        Slider(
            value = track.volume.coerceIn(0f, 1f),
            onValueChange = onVolume,
            valueRange = 0f..1f,
            enabled = !recording && canRecord
        )
    }
}
