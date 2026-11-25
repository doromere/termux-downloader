package io.github.doromere.downloader.domain

import io.github.doromere.downloader.data.CatalogTrack
import java.nio.charset.Charset

data class ParsedPlaylist(
    val name: String,
    val kind: String,
    val tracks: List<CatalogTrack>
)

object PlaylistDumpParser {
    private val titleHeaders = setOf(
        "track name", "track", "title", "name", "song", "song name", "track title",
        "название", "трек", "песня", "название трека"
    )
    private val artistHeaders = setOf(
        "artist name", "artist name(s)", "artist", "artists", "artist(s)", "artist names",
        "исполнитель", "исполнители", "артист"
    )
    private val playlistHeaders = setOf("playlist name", "playlist", "плейлист")

    private val numDotRegex = Regex("""^\d{1,4}[.)]\s+""")
    private val numDashRegex = Regex("""^\d{1,4}\s*[-–—]\s+""")
    private val dashSplitRegex = Regex("""\s[-–—]\s""")
    private val schemeRegex = Regex("""^[a-zA-Z]+://""")

    fun decodeBytes(raw: ByteArray): String {
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return String(raw, 3, raw.size - 3, Charsets.UTF_8)
        }
        if (raw.size >= 2 && (
                (raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) ||
                    (raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte())
                )
        ) {
            return String(raw, Charsets.UTF_16)
        }
        return runCatching {
            val dec = Charsets.UTF_8.newDecoder()
            dec.decode(java.nio.ByteBuffer.wrap(raw)).toString()
        }.getOrElse {
            String(raw, Charset.forName("windows-1251"))
        }
    }

    fun stripTrackNumber(line: String): String {
        val noDot = numDotRegex.replaceFirst(line, "")
        if (noDot != line) return noDot
        val m = numDashRegex.find(line) ?: return line
        val rest = line.substring(m.range.last + 1)
        return if (dashSplitRegex.containsMatchIn(rest)) rest else line
    }

    fun parse(raw: ByteArray, fileName: String): ParsedPlaylist {
        val text = decodeBytes(raw)
        val fallbackName = fileName.substringBeforeLast('.', fileName).ifBlank { "Playlist" }
        val csvResult = tryParseCsv(text, fallbackName)
        if (csvResult != null) return csvResult
        return parseTxt(text, fallbackName)
    }

    private fun tryParseCsv(text: String, fallbackName: String): ParsedPlaylist? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return null

        val delimiter = detectDelimiter(lines.take(15)) ?: return null
        val rows = parseCsvRows(text, delimiter).filter { row -> row.any { it.isNotBlank() } }
        if (rows.size < 2) return null

        val header = rows.first()
        var titleIdx = -1
        var artistIdx = -1
        var playlistIdx = -1
        for ((idx, cell) in header.withIndex()) {
            val norm = cell.trim().trim('"').lowercase().replace(Regex("""\s+"""), " ")
            if (titleIdx == -1 && norm in titleHeaders) titleIdx = idx
            if (artistIdx == -1 && norm in artistHeaders) artistIdx = idx
            if (playlistIdx == -1 && norm in playlistHeaders) playlistIdx = idx
        }
        if (titleIdx == -1) return null

        val tracks = mutableListOf<CatalogTrack>()
        var detectedPlaylist = ""
        for (row in rows.drop(1)) {
            val title = row.getOrNull(titleIdx)?.trim().orEmpty()
            if (title.isBlank()) continue
            val artist = if (artistIdx >= 0) row.getOrNull(artistIdx)?.trim().orEmpty() else ""
            if (detectedPlaylist.isBlank() && playlistIdx >= 0) {
                detectedPlaylist = row.getOrNull(playlistIdx)?.trim().orEmpty()
            }
            tracks.add(CatalogTrack(title = title, artist = artist))
        }
        if (tracks.isEmpty()) return null
        return ParsedPlaylist(
            name = detectedPlaylist.ifBlank { fallbackName },
            kind = "CSV",
            tracks = tracks
        )
    }

    private fun parseTxt(text: String, fallbackName: String): ParsedPlaylist {
        val tracks = mutableListOf<CatalogTrack>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim().trimStart('\uFEFF')
            if (line.isBlank() || line.startsWith("#")) continue
            val clean = stripTrackNumber(line)
            if (schemeRegex.containsMatchIn(clean) || clean.startsWith("spotify:track:")) {
                continue
            }
            val parts = dashSplitRegex.split(clean, limit = 2)
            if (parts.size == 2) {
                tracks.add(CatalogTrack(title = parts[1].trim(), artist = parts[0].trim()))
            } else {
                tracks.add(CatalogTrack(title = clean, artist = ""))
            }
        }
        return ParsedPlaylist(name = fallbackName, kind = "TXT", tracks = tracks)
    }

    private fun detectDelimiter(sampleLines: List<String>): Char? {
        val candidates = charArrayOf(',', ';', '\t', '|')
        var bestChar: Char? = null
        var bestScore = 0
        for (c in candidates) {
            val counts = sampleLines.map { line -> countUnquoted(line, c) }
            val first = counts.first()
            if (first >= 1 && counts.all { it == first }) {
                if (first > bestScore) {
                    bestScore = first
                    bestChar = c
                }
            }
        }
        return bestChar
    }

    private fun countUnquoted(line: String, delim: Char): Int {
        var inQuotes = false
        var count = 0
        for (ch in line) {
            if (ch == '"') inQuotes = !inQuotes
            else if (ch == delim && !inQuotes) count++
        }
        return count
    }

    private fun parseCsvRows(text: String, delim: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    delim -> {
                        row.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        row.add(field.toString())
                        rows.add(row)
                        row = mutableListOf()
                        field.setLength(0)
                    }
                    '\n' -> {
                        row.add(field.toString())
                        rows.add(row)
                        row = mutableListOf()
                        field.setLength(0)
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
