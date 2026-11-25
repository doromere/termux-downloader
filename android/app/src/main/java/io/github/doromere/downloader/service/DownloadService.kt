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
                DownloadKind.AUDIO -> downloadWithYtDlp(task, task.url, staging, audioOnly = true)
                DownloadKind.VIDEO, DownloadKind.GENERIC -> {
                    val audioOnly = task.quality == VideoQuality.AUDIO_ONLY
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
                DownloadRepository.updateTask(task.id) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        progress = 100f,
                        detail = "Сохранено (${saved.size} файл.)",
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
            val msg = e.message?.take(180) ?: "Неизвестная ошибка"
            failTask(task, msg)
        } finally {
            staging.deleteRecursively()
        }
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
        val total = tracks.size
        if (total == 0) throw IllegalStateException("Список треков пуст")

        val targetDir = if (!folderName.isNullOrBlank()) {
            File(staging, folderName.replace(Regex("""[\\/:*?"<>|]+"""), "_")).apply { mkdirs() }
        } else {
            staging
        }

        for ((idx, track) in tracks.withIndex()) {
            if (cancelledIds.contains(task.id) ||
                DownloadRepository.getTask(task.id)?.status == DownloadStatus.CANCELLED
            ) {
                return
            }
            val query = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" ")
            val overallPct = ((idx.toFloat() / total) * 100f).coerceIn(0f, 99f)
            val label = "${idx + 1}/$total: $query"
            DownloadRepository.updateTask(task.id) {
                it.copy(progress = overallPct, detail = label)
            }
            updateNotification(task.service, label, task.id, overallPct.toInt(), indeterminate = false)

            val request = buildAudioRequest("ytsearch3:$query", targetDir, task.audioFormat).apply {
                addOption("--match-filter", "duration<=?900 & !is_live")
                addOption("--max-downloads", "1")
            }
            runCatching {
                YoutubeDL.getInstance().execute(request, task.id) { _, _, _ -> }
            }
        }
        val saved = StorageSaver.saveFilesToDownloads(this, targetDir, task.service, folderName)
        if (saved.isEmpty()) throw IllegalStateException("Не удалось скачать треки с YouTube")
        val first = saved.first()
        DownloadRepository.updateTask(task.id) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100f,
                detail = "Сохранено (${saved.size} из $total)",
                savedPath = first.displayPath,
                savedUri = first.contentUri
            )
        }
        postResultNotification(task.service, "Скачано ${saved.size} треков → ${task.service}")
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
            DownloadRepository.updateTask(task.id) {
                it.copy(progress = pct, detail = msg)
            }
            val now = System.currentTimeMillis()
            if (now - lastNotifMs >= 700L) {
                lastNotifMs = now
                updateNotification(task.service, msg, task.id, pct.toInt(), progress < 0f)
            }
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
            addOption("-o", File(staging, "%(title).120B [%(id)s].%(ext)s").absolutePath)
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
            addOption("-o", File(staging, "%(title).120B [%(id)s].%(ext)s").absolutePath)
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
                            DownloadRepository.updateTask(task.id) { it.copy(progress = pct, detail = msg) }
                            updateNotification(task.service, msg, task.id, pct.toInt(), total <= 0)
                        }
                    }
                }
            }
        } finally {
            activeConn.disconnect()
        }
    }

    private fun failTask(task: DownloadTask, msg: String) {
        DownloadRepository.updateTask(task.id) {
            it.copy(status = DownloadStatus.FAILED, detail = msg)
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
