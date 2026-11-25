package io.github.doromere.downloader

import io.github.doromere.downloader.data.TrackProgress
import io.github.doromere.downloader.data.TrackStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadModelsTest {
    @Test
    fun formatsTrackDisplayTitle() {
        val withArtist = TrackProgress(index = 0, title = "Bangarang", artist = "Skrillex", status = TrackStatus.QUEUED)
        assertEquals("Skrillex — Bangarang", withArtist.displayTitle)

        val titleOnly = TrackProgress(index = 1, title = "Right In", artist = "", status = TrackStatus.COMPLETED, progress = 100f)
        assertEquals("Right In", titleOnly.displayTitle)
    }
}
