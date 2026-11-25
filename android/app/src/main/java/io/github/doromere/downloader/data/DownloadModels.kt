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

enum class TrackStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TrackProgress(
    val index: Int,
    val title: String,
    val artist: String = "",
    val status: TrackStatus = TrackStatus.QUEUED,
    val progress: Float = 0f
) {
    val displayTitle: String
        get() = if (artist.isNotBlank()) "$artist — $title" else title
}

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val service: String,
    val kind: DownloadKind,
    val quality: VideoQuality,
    val audioFormat: AudioFormat,
    val title: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val detail: String = "В очереди",
    val savedPath: String? = null,
    val savedUri: String? = null,
    val tracks: List<TrackProgress> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class AppSettings(
    val defaultQuality: VideoQuality = VideoQuality.BEST,
    val audioFormat: AudioFormat = AudioFormat.MP3
)
