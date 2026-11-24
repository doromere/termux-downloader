package io.github.doromere.downloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.doromere.downloader.data.AppSettings
import io.github.doromere.downloader.data.DownloadRepository
import io.github.doromere.downloader.data.DownloadStatus
import io.github.doromere.downloader.data.DownloadTask
import io.github.doromere.downloader.domain.AudioFormat
import io.github.doromere.downloader.domain.VideoQuality

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloaderScreen(
    inputUrl: String,
    onInputUrlChange: (String) -> Unit,
    initError: String?,
    statusBanner: String?,
    isUpdatingYtDlp: Boolean,
    onStartDownload: (String, VideoQuality) -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (DownloadTask) -> Unit,
    onRemoveTask: (String) -> Unit,
    onClearFinished: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onUpdateYtDlp: () -> Unit
) {
    val tasks by DownloadRepository.tasks.collectAsState()
    val settings by DownloadRepository.settings.collectAsState()
    var selectedQuality by remember(settings.defaultQuality) { mutableStateOf(settings.defaultQuality) }
    var showSettings by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media Downloader") },
                actions = {
                    if (tasks.any { it.status != DownloadStatus.RUNNING && it.status != DownloadStatus.QUEUED }) {
                        IconButton(onClick = onClearFinished) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Очистить завершённые")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (initError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "Ошибка инициализации движка: $initError",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (statusBanner != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        text = statusBanner,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            OutlinedTextField(
                value = inputUrl,
                onValueChange = onInputUrlChange,
                label = { Text("Ссылка или текст со ссылкой") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { onInputUrlChange(it) }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Вставить")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                VideoQuality.entries.forEach { q ->
                    FilterChip(
                        selected = selectedQuality == q,
                        onClick = { selectedQuality = q },
                        label = { Text(q.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onStartDownload(inputUrl, selectedQuality) },
                enabled = inputUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text("Скачать")
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (tasks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Поделитесь ссылкой из любого приложения\nили вставьте её выше",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onCancel = { onCancelTask(task.id) },
                            onRetry = { onRetryTask(task) },
                            onRemove = { onRemoveTask(task.id) }
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            isUpdating = isUpdatingYtDlp,
            onDismiss = { showSettings = false },
            onSave = {
                onUpdateSettings(it)
                showSettings = false
            },
            onUpdateYtDlp = onUpdateYtDlp
        )
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("${task.service} • ${task.quality.label}") }
                )
                Row {
                    if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Отменить")
                        }
                    } else {
                        if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED) {
                            IconButton(onClick = onRetry) {
                                Icon(Icons.Default.Refresh, contentDescription = "Повторить")
                            }
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить")
                        }
                    }
                }
            }
            Text(
                text = task.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (task.status == DownloadStatus.RUNNING) {
                if (task.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = task.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (task.status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (task.savedPath != null) {
                Text(
                    text = task.savedPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsDialog(
    settings: AppSettings,
    isUpdating: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onUpdateYtDlp: () -> Unit
) {
    var quality by remember { mutableStateOf(settings.defaultQuality) }
    var audioFmt by remember { mutableStateOf(settings.audioFormat) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Качество по умолчанию", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoQuality.entries.forEach { q ->
                        FilterChip(
                            selected = quality == q,
                            onClick = { quality = q },
                            label = { Text(q.label) }
                        )
                    }
                }
                Text("Формат аудио", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioFormat.entries.forEach { f ->
                        FilterChip(
                            selected = audioFmt == f,
                            onClick = { audioFmt = f },
                            label = { Text(f.ext.uppercase()) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = onUpdateYtDlp,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isUpdating) "Обновление yt-dlp…" else "Обновить движок yt-dlp")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(AppSettings(quality, audioFmt)) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
