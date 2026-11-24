package io.github.doromere.downloader.data

import io.github.doromere.downloader.domain.AudioFormat
import io.github.doromere.downloader.domain.DownloadKind
import io.github.doromere.downloader.domain.VideoQuality
import java.util.UUID

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val service: String,
    val kind: DownloadKind,
    val quality: VideoQuality,
    val audioFormat: AudioFormat,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val detail: String = "В очереди",
    val savedPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class AppSettings(
    val defaultQuality: VideoQuality = VideoQuality.BEST,
    val audioFormat: AudioFormat = AudioFormat.MP3
)
