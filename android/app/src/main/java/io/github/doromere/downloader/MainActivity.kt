package io.github.doromere.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import io.github.doromere.downloader.data.DownloadRepository
import io.github.doromere.downloader.data.DownloadTask
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

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
                    onCancelTask = { id -> DownloadService.cancel(this, id) },
                    onRetryTask = { task ->
                        enqueueMatch(
                            ServiceMatch(task.url, task.service, task.kind, false),
                            task.quality
                        )
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
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
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
