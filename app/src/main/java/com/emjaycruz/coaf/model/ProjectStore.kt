package com.emjaycruz.coaf.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProjectStore(context: Context) {

    private val projectsDir = File(
        context.filesDir,
        "coaf_projects"
    ).apply {
        mkdirs()
    }

    fun saveProject(project: StudioProject) {
        require(project.id.matches(Regex("[a-zA-Z0-9_-]+"))) {
            "Invalid project ID"
        }

        val json = JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("createdAt", project.createdAt)
            put("updatedAt", System.currentTimeMillis())

            put("tracks", JSONArray().apply {
                project.tracks.forEach { track ->
                    put(JSONObject().apply {
                        put("id", track.id)
                        put("name", track.name)
                        put("type", track.type.name)
                        put("isMuted", track.isMuted)
                        put("isSolo", track.isSolo)
                        put("volume", track.volume)
                        put("pan", track.pan)

                        put("recordings", JSONArray().apply {
                            track.recordings.forEach { take ->
                                put(JSONObject().apply {
                                    put("id", take.id)
                                    put("name", take.name)
                                    put("audioPath", take.audioPath)
                                    put("durationMs", take.durationMs)
                                    put("createdAt", take.createdAt)
                                })
                            }
                        })
                    })
                }
            })
        }

        val destination = File(projectsDir, "${project.id}.json")
        val temporary = File(projectsDir, "${project.id}.tmp")

        try {
            temporary.writeText(json.toString())

            if (!temporary.renameTo(destination)) {
                destination.writeText(temporary.readText())
                temporary.delete()
            }
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    fun loadProject(projectId: String): StudioProject? {
        if (!projectId.matches(Regex("[a-zA-Z0-9_-]+"))) {
            return null
        }

        val file = File(projectsDir, "$projectId.json")
        if (!file.exists()) return null

        return try {
            val json = JSONObject(file.readText())
            val tracksJson = json.optJSONArray("tracks") ?: JSONArray()

            val tracks = (0 until tracksJson.length()).map { index ->
                val item = tracksJson.getJSONObject(index)
                val takesJson =
                    item.optJSONArray("recordings") ?: JSONArray()

                val takes = (0 until takesJson.length()).map { takeIndex ->
                    val take = takesJson.getJSONObject(takeIndex)

                    RecordingTake(
                        id = take.getString("id"),
                        name = take.getString("name"),
                        audioPath = take.getString("audioPath"),
                        durationMs = take.optLong("durationMs", 0L),
                        createdAt = take.optLong(
                            "createdAt",
                            System.currentTimeMillis()
                        )
                    )
                }

                StudioTrack(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    type = TrackType.valueOf(item.getString("type")),
                    recordings = takes,
                    isMuted = item.optBoolean("isMuted", false),
                    isSolo = item.optBoolean("isSolo", false),
                    volume = item.optDouble("volume", 1.0).toFloat()
                        .coerceIn(0f, 1f),
                    pan = item.optDouble("pan", 0.0).toFloat()
                        .coerceIn(-1f, 1f)
                )
            }

            StudioProject(
                id = json.getString("id"),
                name = json.getString("name"),
                tracks = tracks,
                createdAt = json.optLong(
                    "createdAt",
                    System.currentTimeMillis()
                ),
                updatedAt = json.optLong(
                    "updatedAt",
                    System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    fun listProjects(): List<StudioProject> {
        return projectsDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file ->
                loadProject(file.nameWithoutExtension)
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun deleteProject(projectId: String): Boolean {
        if (!projectId.matches(Regex("[a-zA-Z0-9_-]+"))) {
            return false
        }

        val file = File(projectsDir, "$projectId.json")
        return !file.exists() || file.delete()
    }
}
