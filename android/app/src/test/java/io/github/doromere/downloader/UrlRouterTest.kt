package io.github.doromere.downloader

import io.github.doromere.downloader.domain.DownloadKind
import io.github.doromere.downloader.domain.UrlRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlRouterTest {
    @Test
    fun extractsAndCleansUrls() {
        val text = "Посмотри https://youtu.be/dQw4w9WgXcQ, и https://en.wikipedia.org/wiki/Foo_(bar)!"
        assertEquals(
            listOf("https://youtu.be/dQw4w9WgXcQ", "https://en.wikipedia.org/wiki/Foo_(bar)"),
            UrlRouter.extractUrls(text)
        )

        val bare = "youtube.com/watch?v=123\nyoutube.com/watch?v=123"
        assertEquals(listOf("youtube.com/watch?v=123"), UrlRouter.extractUrls(bare))
    }

    @Test
    fun rejectsOptionInjectionAndUnknownSchemes() {
        assertNull(UrlRouter.normalizeUrl("--exec=id"))
        assertNull(UrlRouter.normalizeUrl("ftp://example.com/a"))
        assertNull(UrlRouter.classify("--exec=id"))
        assertEquals("https://youtu.be/abc", UrlRouter.normalizeUrl("youtu.be/abc"))
    }

    @Test
    fun classifiesServicesAndMassLinks() {
        val yt = UrlRouter.classify("https://www.youtube.com/watch?v=abc")
        assertNotNull(yt)
        assertEquals("YouTube", yt!!.service)
        assertEquals(DownloadKind.VIDEO, yt.kind)
        assertFalse(yt.isMass)

        val ytPl = UrlRouter.classify("https://www.youtube.com/playlist?list=PL123")
        assertTrue(ytPl!!.isMass)

        val ytm = UrlRouter.classify("https://music.youtube.com/watch?v=1")
        assertEquals("YouTube Music", ytm!!.service)
        assertEquals(DownloadKind.AUDIO, ytm.kind)

        val deezer = UrlRouter.classify("https://www.deezer.com/album/302127")
        assertEquals("Deezer", deezer!!.service)
        assertEquals(DownloadKind.MUSIC_CATALOG, deezer.kind)
        assertTrue(deezer.isMass)

        val drm = UrlRouter.classify("https://www.netflix.com/title/123")
        assertEquals(DownloadKind.DRM_BLOCKED, drm!!.kind)

        val file = UrlRouter.classify("https://example.com/app.apk?v=2")
        assertEquals("Files", file!!.service)
        assertEquals(DownloadKind.DIRECT_FILE, file.kind)

        val ymTrack = UrlRouter.classify("https://music.yandex.ru/album/123/track/456")
        assertEquals("Yandex Music", ymTrack!!.service)
        assertEquals(DownloadKind.AUDIO, ymTrack.kind)
        assertFalse(ymTrack.isMass)

        val ymAlbum = UrlRouter.classify("https://music.yandex.ru/album/123")
        assertEquals("Yandex Music", ymAlbum!!.service)
        assertTrue(ymAlbum.isMass)
    }
}
