package io.github.doromere.downloader

import io.github.doromere.downloader.domain.PlaylistDumpParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class PlaylistDumpParserTest {
    @Test
    fun parsesCsvWithBomAndQuotedFields() {
        val csv = "\uFEFF\"Track name\",\"Artist name\",\"Playlist name\"\n" +
            "\"Song, Part 1\",\"Daft Punk\",\"My Mix\"\n" +
            "\"Track \"\"Two\"\"\",\"Artist 2\",\"My Mix\"\n"
        val parsed = PlaylistDumpParser.parse(csv.toByteArray(Charsets.UTF_8), "fallback.csv")
        assertEquals("CSV", parsed.kind)
        assertEquals("My Mix", parsed.name)
        assertEquals(2, parsed.tracks.size)
        assertEquals("Song, Part 1", parsed.tracks[0].title)
        assertEquals("Daft Punk", parsed.tracks[0].artist)
        assertEquals("Track \"Two\"", parsed.tracks[1].title)
    }

    @Test
    fun parsesCp1251TxtAndPreservesNumericBandNames() {
        val txt = "# comment\n01. Артист - Песня\n02 - 311 - Amber\n311 - Down\nhttps://example.com/skip\n"
        val raw = txt.toByteArray(Charset.forName("windows-1251"))
        val parsed = PlaylistDumpParser.parse(raw, "favorites.txt")
        assertEquals("TXT", parsed.kind)
        assertEquals("favorites", parsed.name)
        assertEquals(3, parsed.tracks.size)
        assertEquals("Артист", parsed.tracks[0].artist)
        assertEquals("Песня", parsed.tracks[0].title)
        assertEquals("311", parsed.tracks[1].artist)
        assertEquals("Amber", parsed.tracks[1].title)
        assertEquals("311", parsed.tracks[2].artist)
        assertEquals("Down", parsed.tracks[2].title)
    }
}
