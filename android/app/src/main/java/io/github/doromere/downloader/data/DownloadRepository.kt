package io.github.doromere.downloader.data

import android.content.Context
import io.github.doromere.downloader.domain.AudioFormat
import io.github.doromere.downloader.domain.DownloadKind
import io.github.doromere.downloader.domain.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DownloadRepository {
    private const val PREFS_NAME = "downloader_prefs"
    private const val KEY_QUALITY = "default_quality"
    private const val KEY_AUDIO_FMT = "audio_format"
    private const val HISTORY_FILE = "tasks.json"

    private lateinit var appContext: Context
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        loadSettings()
        loadHistory()
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_QUALITY, newSettings.defaultQuality.name)
            .putString(KEY_AUDIO_FMT, newSettings.audioFormat.name)
            .apply()
    }

    fun enqueue(task: DownloadTask) {
        _tasks.update { listOf(task) + it }
        saveHistory()
    }

    fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
        saveHistory()
    }

    fun updateTaskInMemory(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    fun savePlaylistTracks(taskId: String, folderName: String, tracks: List<CatalogTrack>) {
        if (!::appContext.isInitialized) return
        val dir = File(appContext.filesDir, "playlists").apply { mkdirs() }
        val arr = JSONArray()
        for (t in tracks) {
            arr.put(JSONObject().apply {
                put("title", t.title)
                put("artist", t.artist)
            })
        }
        val payload = JSONObject().apply {
            put("name", folderName)
            put("tracks", arr)
        }
        File(dir, "$taskId.json").writeText(payload.toString(), Charsets.UTF_8)
    }

    fun loadPlaylistTracks(taskId: String): CatalogResult? {
        if (!::appContext.isInitialized) return null
        val f = File(appContext.filesDir, "playlists/$taskId.json")
        if (!f.isFile) return null
        return runCatching {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val name = obj.optString("name").ifBlank { "Playlist" }
            val arr = obj.optJSONArray("tracks") ?: return null
            val list = ArrayList<CatalogTrack>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                list.add(CatalogTrack(item.optString("title"), item.optString("artist")))
            }
            CatalogResult(name, list)
        }.getOrNull()
    }

    fun removeTask(id: String) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        if (::appContext.isInitialized) {
            File(appContext.filesDir, "playlists/$id.json").delete()
        }
        saveHistory()
    }

    fun clearFinished() {
        val removedIds = _tasks.value
            .filter { it.status != DownloadStatus.QUEUED && it.status != DownloadStatus.RUNNING }
            .map { it.id }
        _tasks.update { list ->
            list.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
        }
        if (::appContext.isInitialized) {
            removedIds.forEach { File(appContext.filesDir, "playlists/$it.json").delete() }
        }
        saveHistory()
    }

    fun getTask(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    private fun loadSettings() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val q = runCatching {
            VideoQuality.valueOf(prefs.getString(KEY_QUALITY, VideoQuality.BEST.name)!!)
        }.getOrDefault(VideoQuality.BEST)
        val af = runCatching {
            AudioFormat.valueOf(prefs.getString(KEY_AUDIO_FMT, AudioFormat.MP3.name)!!)
        }.getOrDefault(AudioFormat.MP3)
        _settings.value = AppSettings(q, af)
    }

    @Synchronized
    private fun saveHistory() {
        if (!::appContext.isInitialized) return
        val arr = JSONArray()
        for (t in _tasks.value.take(100)) {
            val tracksArr = JSONArray()
            for (tr in t.tracks) {
                tracksArr.put(
                    JSONObject().apply {
                        put("index", tr.index)
                        put("title", tr.title)
                        put("artist", tr.artist)
                        put("status", tr.status.name)
                        put("progress", tr.progress.toDouble())
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("url", t.url)
                    put("service", t.service)
                    put("kind", t.kind.name)
                    put("quality", t.quality.name)
                    put("audioFormat", t.audioFormat.name)
                    put("title", t.title ?: JSONObject.NULL)
                    put("status", t.status.name)
                    put("progress", t.progress.toDouble())
                    put("detail", t.detail)
                    put("savedPath", t.savedPath ?: JSONObject.NULL)
                    put("savedUri", t.savedUri ?: JSONObject.NULL)
                    put("tracks", tracksArr)
                    put("createdAt", t.createdAt)
                }
            )
        }
        runCatching {
            File(appContext.filesDir, HISTORY_FILE).writeText(arr.toString(), Charsets.UTF_8)
        }
    }

    private fun loadHistory() {
        val f = File(appContext.filesDir, HISTORY_FILE)
        if (!f.isFile) return
        runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val loaded = mutableListOf<DownloadTask>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rawStatus = DownloadStatus.valueOf(o.getString("status"))
                val status = if (rawStatus == DownloadStatus.RUNNING || rawStatus == DownloadStatus.QUEUED) {
                    DownloadStatus.FAILED
                } else {
                    rawStatus
                }
                val detail = if (status != rawStatus) "Прервано при перезапуске" else o.optString("detail")
                val tracks = mutableListOf<TrackProgress>()
                val tracksArr = o.optJSONArray("tracks")
                if (tracksArr != null) {
                    for (j in 0 until tracksArr.length()) {
                        val to = tracksArr.getJSONObject(j)
                        val rawTs = runCatching { TrackStatus.valueOf(to.optString("status")) }
                            .getOrDefault(TrackStatus.QUEUED)
                        val ts = if (rawTs == TrackStatus.RUNNING || rawTs == TrackStatus.QUEUED) {
                            TrackStatus.FAILED
                        } else {
                            rawTs
                        }
                        tracks.add(
                            TrackProgress(
                                index = to.optInt("index", j),
                                title = to.optString("title"),
                                artist = to.optString("artist"),
                                status = ts,
                                progress = to.optDouble("progress", 0.0).toFloat()
                            )
                        )
                    }
                }
                loaded.add(
                    DownloadTask(
                        id = o.getString("id"),
                        url = o.getString("url"),
                        service = o.getString("service"),
                        kind = DownloadKind.valueOf(o.getString("kind")),
                        quality = VideoQuality.valueOf(o.getString("quality")),
                        audioFormat = AudioFormat.valueOf(o.getString("audioFormat")),
                        title = if (o.isNull("title")) null else o.optString("title"),
                        status = status,
                        progress = o.optDouble("progress", 0.0).toFloat(),
                        detail = detail,
                        savedPath = if (o.isNull("savedPath")) null else o.optString("savedPath"),
                        savedUri = if (o.isNull("savedUri")) null else o.optString("savedUri"),
                        tracks = tracks,
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            _tasks.value = loaded
        }
    }
}
