package io.github.doromere.downloader.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.doromere.downloader.DownloaderApplication
import io.github.doromere.downloader.MainActivity
import io.github.doromere.downloader.data.CatalogTrack
import io.github.doromere.downloader.data.DownloadRepository
import io.github.doromere.downloader.data.DownloadStatus
import io.github.doromere.downloader.data.DownloadTask
import io.github.doromere.downloader.data.MusicCatalogResolver
import io.github.doromere.downloader.data.StorageSaver
import io.github.doromere.downloader.data.TrackProgress
import io.github.doromere.downloader.data.TrackStatus
import io.github.doromere.downloader.domain.AudioFormat
import io.github.doromere.downloader.domain.DownloadKind
import io.github.doromere.downloader.domain.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private var workerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        workerJob = serviceScope.launch {
            for (taskId in queue) {
                if (cancelledIds.remove(taskId)) continue
                val task = DownloadRepository.getTask(taskId) ?: continue
                if (task.status == DownloadStatus.CANCELLED) continue
                runTask(task)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    cancelledIds.add(taskId)
                    runCatching { YoutubeDL.getInstance().destroyProcessById(taskId) }
                    DownloadRepository.updateTask(taskId) {
                        if (it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED) {
                            it
                        } else {
                            it.copy(status = DownloadStatus.CANCELLED, detail = "Отменено")
                        }
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_ENQUEUE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    startForegroundCompat("Подготовка загрузки…", taskId, 0, indeterminate = true)
                    queue.trySend(taskId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runTask(task: DownloadTask) {
        val staging = File(cacheDir, "staging/${task.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            DownloadRepository.updateTask(task.id) {
                it.copy(status = DownloadStatus.RUNNING, progress = 0f, detail = "Запуск…")
            }
            updateNotification(task.service, "Подготовка…", task.id, 0, indeterminate = true)

            when (task.kind) {
                DownloadKind.DRM_BLOCKED -> {
                    failTask(task, "Сервис защищён DRM (Widevine/FairPlay). Обход не поддерживается.")
                    return
                }
                DownloadKind.UNSUPPORTED_MUSIC -> {
                    failTask(task, "${task.service}: нет открытого API. Используйте Deezer/Apple Music или YouTube Music.")
                    return
                }
                DownloadKind.DIRECT_FILE -> downloadDirectFile(task, staging)
                DownloadKind.MUSIC_CATALOG -> {
                    downloadMusicCatalog(task, staging)
                    return
                }
                DownloadKind.PLAYLIST_DUMP -> {
                    downloadPlaylistDump(task, staging)
                    return
                }
                DownloadKind.AUDIO -> {
                    if (isYtPlaylistUrl(task.url) && tryDownloadYtPlaylist(task, staging, audioOnly = true)) {
                        return
                    }
                    downloadWithYtDlp(task, task.url, staging, audioOnly = true)
                }
                DownloadKind.VIDEO, DownloadKind.GENERIC -> {
                    val audioOnly = task.quality == VideoQuality.AUDIO_ONLY
                    if (isYtPlaylistUrl(task.url) && tryDownloadYtPlaylist(task, staging, audioOnly = audioOnly)) {
                        return
                    }
                    downloadWithYtDlp(task, task.url, staging, audioOnly = audioOnly)
                }
            }

            if (cancelledIds.contains(task.id) ||
                DownloadRepository.getTask(task.id)?.status == DownloadStatus.CANCELLED
            ) {
                return
            }

            val saved = StorageSaver.saveFilesToDownloads(this, staging, task.service)
            if (saved.isEmpty()) {
                failTask(task, "Файл не был создан загрузчиком")
            } else {
                val first = saved.first()
                val fileTitle = first.displayPath.substringAfterLast('/').substringBeforeLast('.')
                DownloadRepository.updateTask(task.id) {
                    it.copy(
                        title = it.title ?: fileTitle,
                        status = DownloadStatus.COMPLETED,
                        progress = 100f,
                        detail = if (saved.size > 1) "Сохранено (${saved.size} файл.)" else "Сохранено",
                        savedPath = first.displayPath,
                        savedUri = first.contentUri
                    )
                }
                postResultNotification(task.service, "Готово: ${first.displayPath.substringAfterLast('/')}")
            }
        } catch (e: InterruptedException) {
            DownloadRepository.updateTask(task.id) {
                it.copy(status = DownloadStatus.CANCELLED, detail = "Отменено")
            }
        } catch (e: Exception) {
            if (cancelledIds.contains(task.id) ||
                DownloadRepository.getTask(task.id)?.status == DownloadStatus.CANCELLED
            ) {
                return
            }
            val msg = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(180) ?: "Неизвестная ошибка"
            val fullLog = buildString {
                if (!e.message.isNullOrBlank()) appendLine(e.message)
                appendLine()
                append(e.stackTraceToString().take(4000))
            }.trim()
            failTask(task, msg, fullLog)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun isYtPlaylistUrl(url: String): Boolean {
        val low = url.lowercase()
        return low.contains("youtube.com/playlist") || low.contains("youtube.com/browse/")
    }

    private fun tryDownloadYtPlaylist(task: DownloadTask, staging: File, audioOnly: Boolean): Boolean {
        DownloadRepository.updateTask(task.id) { it.copy(detail = "Запрос списка треков…") }
        val dumpReq = YoutubeDLRequest(task.url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
        }
        val jsonText = runCatching {
            YoutubeDL.getInstance().execute(dumpReq, task.id) { _, _, _ -> }.out
        }.getOrNull() ?: return false

        val obj = runCatching { org.json.JSONObject(jsonText) }.getOrNull() ?: return false
        val entries = obj.optJSONArray("entries") ?: return false
        if (entries.length() == 0) return false

        val playlistTitle = obj.optString("title").ifBlank { task.service }
        val items = mutableListOf<Pair<String, TrackProgress>>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val entryUrl = e.optString("url").ifBlank {
                val id = e.optString("id")
                if (id.isNotBlank()) "https://www.youtube.com/watch?v=$id" else ""
            }
            if (entryUrl.isBlank()) continue
            val title = e.optString("title").ifBlank { "Трек ${i + 1}" }
            val artist = e.optString("uploader").ifBlank { e.optString("channel") }
            items.add(entryUrl to TrackProgress(index = items.size, title = title, artist = artist))
        }
        if (items.isEmpty()) return false

        executeTrackQueue(task, staging, playlistTitle, items, audioOnly = audioOnly, isSearch = false)
        return true
    }

    private fun downloadMusicCatalog(task: DownloadTask, staging: File) {
        DownloadRepository.updateTask(task.id) { it.copy(detail = "Запрос треклиста…") }
        val catalog = MusicCatalogResolver.resolve(task.url)
        downloadTrackList(task, staging, catalog.folderName, catalog.tracks)
    }

    private fun downloadPlaylistDump(task: DownloadTask, staging: File) {
        val loaded = DownloadRepository.loadPlaylistTracks(task.id)
            ?: throw IllegalStateException("Список треков не найден")
        downloadTrackList(task, staging, loaded.folderName, loaded.tracks)
    }

    private fun downloadTrackList(
        task: DownloadTask,
        staging: File,
        folderName: String?,
        tracks: List<CatalogTrack>
    ) {
        if (tracks.isEmpty()) throw IllegalStateException("Список треков пуст")
        val items = tracks.mapIndexed { idx, t ->
            val query = listOf(t.artist, t.title).filter { it.isNotBlank() }.joinToString(" ")
            "ytsearch3:$query" to TrackProgress(index = idx, title = t.title, artist = t.artist)
        }
        executeTrackQueue(task, staging, folderName, items, audioOnly = true, isSearch = true)
    }

    private fun executeTrackQueue(
        task: DownloadTask,
        staging: File,
        folderName: String?,
        items: List<Pair<String, TrackProgress>>,
        audioOnly: Boolean,
        isSearch: Boolean
    ) {
        val total = items.size
        val trackList = items.map { it.second }
        val cleanTitle = folderName?.takeIf { it.isNotBlank() }
            ?: if (total == 1) trackList.first().displayTitle else "${task.service} ($total треков)"

        DownloadRepository.updateTask(task.id) {
            it.copy(title = cleanTitle, tracks = trackList)
        }

        val targetDir = if (!folderName.isNullOrBlank()) {
            File(staging, folderName.replace(Regex("""[\\/:*?"<>|]+"""), "_")).apply { mkdirs() }
        } else {
            staging
        }

        var lastNotifMs = 0L
        var completedCount = 0

        for ((idx, pair) in items.withIndex()) {
            if (cancelledIds.contains(task.id) ||
                DownloadRepository.getTask(task.id)?.status == DownloadStatus.CANCELLED
            ) {
                markRemainingTracksCancelled(task.id)
                return
            }
            val (targetUrl, trackInfo) = pair
            val trackLabel = trackInfo.displayTitle
            val basePct = (idx.toFloat() / total) * 100f

            DownloadRepository.updateTask(task.id) { current ->
                current.copy(
                    progress = basePct,
                    detail = "${idx + 1}/$total: $trackLabel",
                    tracks = current.tracks.map {
                        if (it.index == idx) it.copy(status = TrackStatus.RUNNING, progress = 0f) else it
                    }
                )
            }
            updateNotification(task.service, "${idx + 1}/$total: $trackLabel", task.id, basePct.toInt(), false)

            val beforeFiles = targetDir.listFiles()?.map { it.name }?.toSet().orEmpty()
            val request = if (audioOnly) {
                buildAudioRequest(targetUrl, targetDir, task.audioFormat)
            } else {
                buildVideoRequest(targetUrl, targetDir, task.quality)
            }
            if (isSearch) {
                request.addOption("--match-filter", "duration<=?900 & !is_live")
                request.addOption("--max-downloads", "1")
            }

            runCatching {
                YoutubeDL.getInstance().execute(request, task.id) { progress, _, _ ->
                    if (cancelledIds.contains(task.id)) return@execute
                    val tPct = if (progress >= 0f) progress.coerceIn(0f, 100f) else 0f
                    val overall = (((idx + tPct / 100f) / total) * 100f).coerceIn(0f, 99f)
                    DownloadRepository.updateTaskInMemory(task.id) { current ->
                        current.copy(
                            progress = overall,
                            tracks = current.tracks.map {
                                if (it.index == idx) it.copy(progress = tPct) else it
                            }
                        )
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastNotifMs >= 800L) {
                        lastNotifMs = now
                        updateNotification(
                            task.service,
                            "${idx + 1}/$total (${tPct.toInt()}%): $trackLabel",
                            task.id,
                            overall.toInt(),
                            false
                        )
                    }
                }
            }

            if (cancelledIds.contains(task.id) ||
                DownloadRepository.getTask(task.id)?.status == DownloadStatus.CANCELLED
            ) {
                markRemainingTracksCancelled(task.id)
                return
            }

            val afterFiles = targetDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.map { it.name }
                ?.toSet()
                .orEmpty()
            val trackDone = (afterFiles - beforeFiles).isNotEmpty()
            if (trackDone) completedCount++

            DownloadRepository.updateTask(task.id) { current ->
                current.copy(
                    tracks = current.tracks.map {
                        if (it.index == idx) {
                            it.copy(
                                status = if (trackDone) TrackStatus.COMPLETED else TrackStatus.FAILED,
                                progress = if (trackDone) 100f else 0f
                            )
                        } else {
                            it
                        }
                    }
                )
            }
        }
        val saved = StorageSaver.saveFilesToDownloads(this, targetDir, task.service, folderName)
        if (saved.isEmpty()) throw IllegalStateException("Не удалось скачать треки")
        val first = saved.first()
        DownloadRepository.updateTask(task.id) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100f,
                detail = "Сохранено ($completedCount из $total)",
                savedPath = first.displayPath,
                savedUri = first.contentUri
            )
        }
        postResultNotification(task.service, "Скачано $completedCount из $total → $cleanTitle")
    }

    private fun markRemainingTracksCancelled(taskId: String) {
        DownloadRepository.updateTask(taskId) { current ->
            current.copy(
                tracks = current.tracks.map {
                    if (it.status == TrackStatus.QUEUED || it.status == TrackStatus.RUNNING) {
                        it.copy(status = TrackStatus.CANCELLED)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private fun downloadWithYtDlp(task: DownloadTask, url: String, staging: File, audioOnly: Boolean) {
        val request = if (audioOnly) {
            buildAudioRequest(url, staging, task.audioFormat)
        } else {
            buildVideoRequest(url, staging, task.quality)
        }
        var lastNotifMs = 0L
        YoutubeDL.getInstance().execute(request, task.id) { progress, eta, line ->
            if (cancelledIds.contains(task.id)) return@execute
            val pct = if (progress >= 0f) progress.coerceIn(0f, 100f) else 0f
            val etaTxt = if (eta > 0) " • ETA ${eta}s" else ""
            val msg = if (progress >= 0f) "${pct.toInt()}%$etaTxt" else line.take(60).ifBlank { "Загрузка…" }
            DownloadRepository.updateTaskInMemory(task.id) {
                it.copy(progress = pct, detail = msg)
            }
            val now = System.currentTimeMillis()
            if (now - lastNotifMs >= 700L) {
                lastNotifMs = now
                updateNotification(task.service, msg, task.id, pct.toInt(), progress < 0f)
            }
        }
    }

    private fun applyCommonOptions(req: YoutubeDLRequest) {
        DownloadRepository.cookiesFile()?.let { cookies ->
            req.addOption("--cookies", cookies.absolutePath)
        }
        if (DownloadRepository.settings.value.useAria2c) {
            req.addOption("--downloader", "libaria2c.so")
        }
    }

    private fun buildVideoRequest(url: String, staging: File, quality: VideoQuality): YoutubeDLRequest {
        val sort = when (quality) {
            VideoQuality.Q1080 -> "res:1080,ext:mp4:m4a"
            VideoQuality.Q720 -> "res:720,ext:mp4:m4a"
            else -> "res,ext:mp4:m4a"
        }
        return YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--embed-metadata")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("-S", sort)
            addOption("--merge-output-format", "mp4")
            addOption("-o", File(staging, "%(title).120B.%(ext)s").absolutePath)
            applyCommonOptions(this)
        }
    }

    private fun buildAudioRequest(url: String, staging: File, audioFormat: AudioFormat): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--embed-metadata")
            addOption("--embed-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            when (audioFormat) {
                AudioFormat.M4A -> {
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                }
                AudioFormat.OPUS -> {
                    addOption("-f", "bestaudio[acodec=opus]/bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "opus")
                }
                AudioFormat.MP3 -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                }
            }
            addOption("-o", File(staging, "%(title).120B.%(ext)s").absolutePath)
            applyCommonOptions(this)
        }
    }

    private fun downloadDirectFile(task: DownloadTask, staging: File) {
        var current = task.url
        var conn: HttpURLConnection? = null
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location").orEmpty()
                c.disconnect()
                if (loc.startsWith("https://") || loc.startsWith("http://")) {
                    current = loc
                } else {
                    throw IllegalStateException("Небезопасный редирект")
                }
            } else {
                conn = c
                return@repeat
            }
        }
        val activeConn = conn ?: throw IllegalStateException("Слишком много редиректов")
        try {
            if (activeConn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${activeConn.responseCode}")
            }
            val disp = activeConn.getHeaderField("Content-Disposition").orEmpty()
            val dispName = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(disp)?.groupValues?.getOrNull(1)
            val urlName = URL(current).path.substringAfterLast('/').ifBlank { "download_${System.currentTimeMillis()}" }
            val rawName = File(dispName ?: urlName).name.ifBlank { "download.bin" }
            val safeName = rawName.replace(Regex("""[\\/:*?"<>|]+"""), "_").trimStart('.')
                .ifBlank { "download.bin" }
            val outFile = File(staging, safeName)
            val total = activeConn.contentLengthLong
            var downloaded = 0L
            var lastNotif = 0L
            val buf = ByteArray(16 * 1024)

            activeConn.inputStream.use { inp ->
                outFile.outputStream().use { out ->
                    while (true) {
                        if (cancelledIds.contains(task.id)) throw InterruptedException("Cancelled")
                        val read = inp.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        downloaded += read
                        val pct = if (total > 0) ((downloaded * 100f) / total).coerceIn(0f, 100f) else 0f
                        val now = System.currentTimeMillis()
                        if (now - lastNotif >= 500L) {
                            lastNotif = now
                            val msg = if (total > 0) "${pct.toInt()}%" else "${downloaded / 1024} KiB"
                            DownloadRepository.updateTaskInMemory(task.id) { it.copy(progress = pct, detail = msg) }
                            updateNotification(task.service, msg, task.id, pct.toInt(), total <= 0)
                        }
                    }
                }
            }
        } finally {
            activeConn.disconnect()
        }
    }

    private fun failTask(task: DownloadTask, msg: String, errorLog: String? = null) {
        DownloadRepository.updateTask(task.id) {
            it.copy(status = DownloadStatus.FAILED, detail = msg, errorLog = errorLog ?: msg)
        }
        postResultNotification("${task.service}: ошибка", msg)
    }

    private fun startForegroundCompat(text: String, taskId: String, progress: Int, indeterminate: Boolean) {
        val notif = buildProgressNotification("Media Downloader", text, taskId, progress, indeterminate)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_PROGRESS_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS_ID, notif)
        }
    }

    private fun updateNotification(title: String, text: String, taskId: String, progress: Int, indeterminate: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_PROGRESS_ID, buildProgressNotification(title, text, taskId, progress, indeterminate))
    }

    private fun buildProgressNotification(
        title: String,
        text: String,
        taskId: String,
        progress: Int,
        indeterminate: Boolean
    ) = NotificationCompat.Builder(this, DownloaderApplication.CHANNEL_DOWNLOADS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, progress, indeterminate)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Отмена",
            PendingIntent.getService(
                this,
                taskId.hashCode(),
                Intent(this, DownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_TASK_ID, taskId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun postResultNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notif = NotificationCompat.Builder(this, DownloaderApplication.CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        nm.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        workerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_PROGRESS_ID = 1001
        const val ACTION_ENQUEUE = "io.github.doromere.downloader.ENQUEUE"
        const val ACTION_CANCEL = "io.github.doromere.downloader.CANCEL"
        const val EXTRA_TASK_ID = "task_id"

        fun enqueue(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_TASK_ID, taskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }
    }
}
