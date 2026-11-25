package io.github.doromere.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import io.github.doromere.downloader.data.DownloadRepository
import io.github.doromere.downloader.data.DownloadTask
import io.github.doromere.downloader.data.TrackProgress
import io.github.doromere.downloader.domain.DownloadKind
import io.github.doromere.downloader.domain.ParsedPlaylist
import io.github.doromere.downloader.domain.PlaylistDumpParser
import io.github.doromere.downloader.domain.ServiceMatch
import io.github.doromere.downloader.domain.UrlRouter
import io.github.doromere.downloader.domain.VideoQuality
import io.github.doromere.downloader.service.DownloadService
import io.github.doromere.downloader.ui.DownloaderScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var inputUrl by mutableStateOf("")
    private var statusBanner by mutableStateOf<String?>(null)
    private var isUpdatingYtDlp by mutableStateOf(false)
    private var pendingMassConfirmation by mutableStateOf<Pair<List<ServiceMatch>, VideoQuality>?>(null)
    private var pendingPlaylistConfirmation by mutableStateOf<ParsedPlaylist?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val openPlaylistLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importPlaylistUri(uri)
        }
    }

    private val openCookiesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importCookiesUri(uri)
        }
    }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            applyCustomFolderUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        handleIncomingIntent(intent)

        val app = application as DownloaderApplication
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                }
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }

            MaterialTheme(colorScheme = scheme) {
                DownloaderScreen(
                    inputUrl = inputUrl,
                    onInputUrlChange = {
                        inputUrl = it
                        statusBanner = null
                    },
                    initError = app.initError,
                    statusBanner = statusBanner,
                    isUpdatingYtDlp = isUpdatingYtDlp,
                    onStartDownload = { raw, quality -> submitInput(raw, quality) },
                    onImportPlaylist = {
                        openPlaylistLauncher.launch(
                            arrayOf("text/plain", "text/csv", "text/comma-separated-values", "application/csv")
                        )
                    },
                    onImportCookies = {
                        openCookiesLauncher.launch(arrayOf("text/plain", "text/*"))
                    },
                    onClearCookies = {
                        DownloadRepository.clearCookies()
                        statusBanner = "Cookies удалены"
                    },
                    onPickSaveFolder = {
                        openFolderLauncher.launch(null)
                    },
                    onResetSaveFolder = {
                        val cur = DownloadRepository.settings.value
                        DownloadRepository.updateSettings(
                            cur.copy(customFolderUri = null, customFolderLabel = null)
                        )
                        statusBanner = "Папка сохранения сброшена на стандартную"
                    },
                    onOpenTask = { task -> openSavedTask(task) },
                    onCancelTask = { id -> DownloadService.cancel(this, id) },
                    onRetryTask = { task ->
                        if (task.kind == DownloadKind.PLAYLIST_DUMP) {
                            val loaded = DownloadRepository.loadPlaylistTracks(task.id)
                            if (loaded != null) {
                                enqueuePlaylist(ParsedPlaylist(loaded.folderName ?: "Playlist", "DUMP", loaded.tracks))
                            } else {
                                statusBanner = "Исходный список треков не найден — импортируйте файл снова."
                            }
                        } else {
                            enqueueMatch(
                                ServiceMatch(task.url, task.service, task.kind, false),
                                task.quality
                            )
                        }
                    },
                    onRemoveTask = { id -> DownloadRepository.removeTask(id) },
                    onClearFinished = { DownloadRepository.clearFinished() },
                    onUpdateSettings = { DownloadRepository.updateSettings(it) },
                    onUpdateYtDlp = { updateYtDlp() }
                )

                pendingMassConfirmation?.let { (matches, quality) ->
                    AlertDialog(
                        onDismissRequest = { pendingMassConfirmation = null },
                        title = { Text("Массовая загрузка") },
                        text = {
                            Text("Ссылка ведёт на канал, альбом или плейлист (${matches.first().service}). Скачать всё?")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingMassConfirmation = null
                                matches.forEach { enqueueMatch(it, quality) }
                                inputUrl = ""
                            }) {
                                Text("Скачать")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingMassConfirmation = null }) {
                                Text("Отмена")
                            }
                        }
                    )
                }

                pendingPlaylistConfirmation?.let { parsed ->
                    AlertDialog(
                        onDismissRequest = { pendingPlaylistConfirmation = null },
                        title = { Text("Импорт плейлиста (${parsed.kind})") },
                        text = {
                            Text("«${parsed.name}» — найдено треков: ${parsed.tracks.size}. Скачать аудио с YouTube?")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingPlaylistConfirmation = null
                                enqueuePlaylist(parsed)
                            }) {
                                Text("Скачать")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingPlaylistConfirmation = null }) {
                                Text("Отмена")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (streamUri != null) {
            importPlaylistUri(streamUri)
            return
        }
        if (intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val urls = UrlRouter.extractUrls(shared)
            if (urls.isNotEmpty()) {
                inputUrl = urls.joinToString(" ")
                val defaultQ = DownloadRepository.settings.value.defaultQuality
                submitInput(inputUrl, defaultQ)
            } else if (shared.isNotBlank()) {
                inputUrl = shared.trim()
            }
        }
    }

    private fun importPlaylistUri(uri: Uri) {
        runCatching {
            val name = queryDisplayName(uri) ?: "playlist.csv"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Не удалось прочитать файл")
            val parsed = PlaylistDumpParser.parse(bytes, name)
            if (parsed.tracks.isEmpty()) {
                statusBanner = "В файле не найдено треков: $name"
            } else {
                pendingPlaylistConfirmation = parsed
            }
        }.onFailure {
            statusBanner = "Ошибка чтения файла: ${it.message}"
        }
    }

    private fun importCookiesUri(uri: Uri) {
        runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Не удалось прочитать файл")
            val text = PlaylistDumpParser.decodeBytes(bytes)
            val hasNetscape = text.lineSequence().any { line ->
                val t = line.trim()
                t.startsWith("# Netscape") || t.startsWith("# HTTP Cookie") || t.count { it == '\t' } >= 5
            }
            if (!hasNetscape) {
                statusBanner = "Файл не похож на Netscape cookies.txt"
                return
            }
            DownloadRepository.saveCookies(text)
            statusBanner = "Cookies импортированы (${bytes.size / 1024} КБ)"
        }.onFailure {
            statusBanner = "Ошибка импорта cookies: ${it.message}"
        }
    }

    private fun applyCustomFolderUri(uri: Uri) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            val label = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "Выбранная папка"
            val cur = DownloadRepository.settings.value
            DownloadRepository.updateSettings(
                cur.copy(customFolderUri = uri.toString(), customFolderLabel = label)
            )
            statusBanner = "Папка сохранения: $label"
        }.onFailure {
            statusBanner = "Не удалось закрепить доступ к папке: ${it.message}"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun openSavedTask(task: DownloadTask) {
        val uriStr = task.savedUri ?: return
        val uri = Uri.parse(uriStr)
        val mime = contentResolver.getType(uri) ?: "*/*"
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(viewIntent, "Открыть файл"))
        }.onFailure {
            statusBanner = "Нет приложения для открытия файла (${task.savedPath ?: uriStr})"
        }
    }

    private fun submitInput(raw: String, quality: VideoQuality) {
        statusBanner = null
        val extracted = UrlRouter.extractUrls(raw)
        val candidates = if (extracted.isNotEmpty()) extracted else listOf(raw.trim())
        val matches = candidates.mapNotNull { UrlRouter.classify(it) }
        if (matches.isEmpty()) {
            statusBanner = "Не удалось распознать корректную http(s)-ссылку."
            return
        }
        if (matches.any { it.isMass }) {
            pendingMassConfirmation = matches to quality
            return
        }
        matches.forEach { enqueueMatch(it, quality) }
        inputUrl = ""
    }

    private fun enqueueMatch(match: ServiceMatch, quality: VideoQuality) {
        val audioFmt = DownloadRepository.settings.value.audioFormat
        val task = DownloadTask(
            url = match.url,
            service = match.service,
            kind = match.kind,
            quality = quality,
            audioFormat = audioFmt
        )
        DownloadRepository.enqueue(task)
        DownloadService.enqueue(this, task.id)
    }

    private fun enqueuePlaylist(parsed: ParsedPlaylist) {
        val audioFmt = DownloadRepository.settings.value.audioFormat
        val task = DownloadTask(
            url = "playlist://${parsed.name} (${parsed.tracks.size})",
            service = "Playlists",
            kind = DownloadKind.PLAYLIST_DUMP,
            quality = VideoQuality.AUDIO_ONLY,
            audioFormat = audioFmt,
            title = parsed.name,
            detail = "Треков: ${parsed.tracks.size}",
            tracks = parsed.tracks.mapIndexed { idx, t ->
                TrackProgress(index = idx, title = t.title, artist = t.artist)
            }
        )
        DownloadRepository.savePlaylistTracks(task.id, parsed.name, parsed.tracks)
        DownloadRepository.enqueue(task)
        DownloadService.enqueue(this, task.id)
    }

    private fun updateYtDlp() {
        if (isUpdatingYtDlp) return
        isUpdatingYtDlp = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val status = YoutubeDL.getInstance().updateYoutubeDL(
                        applicationContext,
                        YoutubeDL.UpdateChannel.STABLE
                    )
                    when (status) {
                        YoutubeDL.UpdateStatus.DONE -> "yt-dlp обновлён до последней версии"
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp уже актуален"
                        else -> "Статус обновления: $status"
                    }
                }.getOrElse { "Ошибка обновления yt-dlp: ${it.message}" }
            }
            statusBanner = result
            isUpdatingYtDlp = false
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}
