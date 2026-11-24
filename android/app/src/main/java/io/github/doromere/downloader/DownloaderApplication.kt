package io.github.doromere.downloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import io.github.doromere.downloader.data.DownloadRepository

class DownloaderApplication : Application() {

    @Volatile
    var initError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        DownloadRepository.init(this)
        createNotificationChannels()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Aria2c.getInstance().init(this)
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "Failed to initialize youtubedl-android", e)
            initError = e.message ?: "Failed to initialize download engine"
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val progressChannel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                "Загрузки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прогресс активных загрузок"
            }
            val resultChannel = NotificationChannel(
                CHANNEL_RESULTS,
                "Результаты загрузок",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о завершении или ошибках"
            }
            nm.createNotificationChannel(progressChannel)
            nm.createNotificationChannel(resultChannel)
        }
    }

    companion object {
        private const val TAG = "DownloaderApp"
        const val CHANNEL_DOWNLOADS = "downloads_progress"
        const val CHANNEL_RESULTS = "downloads_results"
    }
}
