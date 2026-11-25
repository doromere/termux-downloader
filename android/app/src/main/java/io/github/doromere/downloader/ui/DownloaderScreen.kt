package io.github.doromere.downloader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.doromere.downloader.data.AppSettings
import io.github.doromere.downloader.data.DownloadRepository
import io.github.doromere.downloader.data.DownloadStatus
import io.github.doromere.downloader.data.DownloadTask
import io.github.doromere.downloader.data.TrackProgress
import io.github.doromere.downloader.data.TrackStatus
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
    onImportPlaylist: () -> Unit,
    onOpenTask: (DownloadTask) -> Unit,
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
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val expandedAlbums = remember { mutableStateMapOf<String, Boolean>() }
    val clipboard = LocalClipboardManager.current

    val groupedTasks = remember(tasks) {
        tasks.groupBy { it.service }.entries.sortedByDescending { (_, list) ->
            list.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
        }
    }

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
                .padding(horizontal = 12.dp)
        ) {
            if (initError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Text(
                        text = "Ошибка инициализации движка: $initError",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (statusBanner != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Text(
                        text = statusBanner,
                        modifier = Modifier.padding(10.dp),
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

            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { onStartDownload(inputUrl, selectedQuality) },
                    enabled = inputUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Скачать")
                }
                OutlinedButton(onClick = onImportPlaylist) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CSV/TXT")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    groupedTasks.forEach { (service, groupTasks) ->
                        val isCollapsed = collapsedGroups[service] == true
                        val activeCount = groupTasks.count {
                            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED
                        }

                        item(key = "hdr_$service") {
                            SourceGroupHeader(
                                service = service,
                                totalCount = groupTasks.size,
                                activeCount = activeCount,
                                collapsed = isCollapsed,
                                onToggle = { collapsedGroups[service] = !isCollapsed }
                            )
                        }

                        if (!isCollapsed) {
                            items(groupTasks, key = { it.id }) { task ->
                                val isAlbumExpanded = expandedAlbums[task.id]
                                    ?: (task.tracks.isNotEmpty() && task.status == DownloadStatus.RUNNING)
                                CompactTaskCard(
                                    task = task,
                                    tracksExpanded = isAlbumExpanded,
                                    onToggleTracks = { expandedAlbums[task.id] = !isAlbumExpanded },
                                    onOpen = { onOpenTask(task) },
                                    onCancel = { onCancelTask(task.id) },
                                    onRetry = { onRetryTask(task) },
                                    onRemove = { onRemoveTask(task.id) }
                                )
                            }
                        }
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
private fun SourceGroupHeader(
    service: String,
    totalCount: Int,
    activeCount: Int,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = service,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (activeCount > 0) "($activeCount активн. / $totalCount)" else "($totalCount)",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (activeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (collapsed) "Развернуть" else "Свернуть",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CompactTaskCard(
    task: DownloadTask,
    tracksExpanded: Boolean,
    onToggleTracks: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title?.takeIf { it.isNotBlank() } ?: task.url,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${task.quality.label} • ${task.detail}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (task.status == DownloadStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                if (task.tracks.isNotEmpty()) {
                    IconButton(onClick = onToggleTracks, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (tracksExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Треки (${task.tracks.size})",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Отменить", modifier = Modifier.size(18.dp))
                    }
                } else {
                    if (task.status == DownloadStatus.COMPLETED && task.savedUri != null) {
                        IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Открыть",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED) {
                        IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Повторить", modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить", modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (task.status == DownloadStatus.RUNNING) {
                Spacer(modifier = Modifier.height(4.dp))
                if (task.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }

            if (task.tracks.isNotEmpty()) {
                AnimatedVisibility(visible = tracksExpanded) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))
                        val doneTracks = task.tracks.count { it.status == TrackStatus.COMPLETED }
                        Text(
                            text = "Треки: $doneTracks / ${task.tracks.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        task.tracks.forEach { track ->
                            TrackProgressRow(track)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackProgressRow(track: TrackProgress) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${track.index + 1}. ${track.displayTitle}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (track.status == TrackStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            when (track.status) {
                TrackStatus.COMPLETED -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Готово",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                TrackStatus.FAILED -> Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = "Ошибка",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                TrackStatus.RUNNING -> Text(
                    text = "${track.progress.toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                TrackStatus.CANCELLED -> Text(
                    text = "отм.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TrackStatus.QUEUED -> Text(
                    text = "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (track.status == TrackStatus.RUNNING) {
            LinearProgressIndicator(
                progress = { (track.progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(top = 1.dp)
            )
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
