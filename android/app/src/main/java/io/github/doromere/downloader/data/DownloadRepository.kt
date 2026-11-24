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

    fun removeTask(id: String) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        saveHistory()
    }

    fun clearFinished() {
        _tasks.update { list ->
            list.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
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
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("url", t.url)
                    put("service", t.service)
                    put("kind", t.kind.name)
                    put("quality", t.quality.name)
                    put("audioFormat", t.audioFormat.name)
                    put("status", t.status.name)
                    put("progress", t.progress.toDouble())
                    put("detail", t.detail)
                    put("savedPath", t.savedPath ?: JSONObject.NULL)
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
                // Any interrupted task from a killed process is marked FAILED on restart
                val status = if (rawStatus == DownloadStatus.RUNNING || rawStatus == DownloadStatus.QUEUED) {
                    DownloadStatus.FAILED
                } else {
                    rawStatus
                }
                val detail = if (status != rawStatus) "Прервано при перезапуске" else o.optString("detail")
                loaded.add(
                    DownloadTask(
                        id = o.getString("id"),
                        url = o.getString("url"),
                        service = o.getString("service"),
                        kind = DownloadKind.valueOf(o.getString("kind")),
                        quality = VideoQuality.valueOf(o.getString("quality")),
                        audioFormat = AudioFormat.valueOf(o.getString("audioFormat")),
                        status = status,
                        progress = o.optDouble("progress", 0.0).toFloat(),
                        detail = detail,
                        savedPath = if (o.isNull("savedPath")) null else o.optString("savedPath"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            _tasks.value = loaded
        }
    }
}
