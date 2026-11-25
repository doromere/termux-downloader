package io.github.doromere.downloader.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CatalogTrack(
    val title: String,
    val artist: String
)

data class CatalogResult(
    val folderName: String?,
    val tracks: List<CatalogTrack>
)

object MusicCatalogResolver {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) MediaDownloader"
    private val deezerRegex = Regex("""deezer\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(track|album|playlist)/(\d+)""")

    fun resolve(url: String): CatalogResult {
        var target = url
        val host = URL(url).host.lowercase()
        if (host in setOf("link.deezer.com", "deezer.page.link", "dzr.page.link")) {
            target = followRedirectsHttpOnly(url)
        }
        val dm = deezerRegex.find(target)
        if (dm != null) {
            return resolveDeezer(dm.groupValues[1], dm.groupValues[2])
        }
        if (host in setOf("music.apple.com", "itunes.apple.com", "geo.music.apple.com")) {
            return resolveApple(target)
        }
        if (host.startsWith("music.yandex.")) {
            return resolveYandex(target)
        }
        throw IllegalArgumentException("Сервис не поддерживается через открытый API")
    }

    private val yandexTrackRegex = Regex("""/track/(\d+)""")
    private val yandexAlbumRegex = Regex("""/album/(\d+)""")
    private val yandexPlaylistRegex = Regex("""/users/([^/]+)/playlists/(\d+)""")

    fun resolveYandex(urlStr: String): CatalogResult {
        val base = "https://music.yandex.ru"
        val tm = yandexTrackRegex.find(urlStr)
        if (tm != null) {
            val tid = tm.groupValues[1]
            val obj = httpJson("$base/handlers/track.jsx?track=$tid")
            val tr = obj.optJSONObject("track") ?: throw IllegalStateException("Яндекс Музыка не отдала трек (капча или нет доступа)")
            val title = tr.optString("title")
            if (title.isBlank()) throw IllegalStateException("Пустое название трека в ответе Яндекса")
            val artists = parseYandexArtists(tr)
            return CatalogResult(null, listOf(CatalogTrack(title, artists)))
        }
        val am = yandexAlbumRegex.find(urlStr)
        if (am != null) {
            val aid = am.groupValues[1]
            val obj = httpJson("$base/handlers/album.jsx?album=$aid")
            val albumTitle = obj.optString("title").ifBlank { "Yandex Album $aid" }
            val albumArtist = parseYandexArtists(obj)
            val tracks = parseYandexVolumes(obj)
            if (tracks.isEmpty()) throw IllegalStateException("Яндекс Музыка не отдала треки альбома (возможна капча)")
            val folder = listOf(albumArtist, albumTitle).filter { it.isNotBlank() }.joinToString(" - ")
            return CatalogResult(folder, tracks)
        }
        val pm = yandexPlaylistRegex.find(urlStr)
        if (pm != null) {
            val owner = URLEncoder.encode(pm.groupValues[1], "UTF-8")
            val kinds = pm.groupValues[2]
            val obj = httpJson("$base/handlers/playlist.jsx?owner=$owner&kinds=$kinds")
            val pl = obj.optJSONObject("playlist") ?: throw IllegalStateException("Яндекс Музыка не отдала плейлист")
            val title = pl.optString("title").ifBlank { "Yandex Playlist $kinds" }
            val arr = pl.optJSONArray("tracks")
            val tracks = mutableListOf<CatalogTrack>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val tName = t.optString("title")
                    if (tName.isNotBlank()) tracks.add(CatalogTrack(tName, parseYandexArtists(t)))
                }
            }
            if (tracks.isEmpty()) throw IllegalStateException("Плейлист пуст или скрыт")
            return CatalogResult(title, tracks)
        }
        throw IllegalArgumentException("Не удалось распознать ссылку Яндекс Музыки")
    }

    private fun parseYandexArtists(obj: JSONObject): String {
        val arr = obj.optJSONArray("artists") ?: return ""
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (n.isNotBlank()) names.add(n)
        }
        return names.joinToString(", ")
    }

    private fun parseYandexVolumes(obj: JSONObject): List<CatalogTrack> {
        val vols = obj.optJSONArray("volumes") ?: return emptyList()
        val tracks = mutableListOf<CatalogTrack>()
        for (v in 0 until vols.length()) {
            val arr = vols.optJSONArray(v) ?: continue
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val title = t.optString("title")
                if (title.isNotBlank()) tracks.add(CatalogTrack(title, parseYandexArtists(t)))
            }
        }
        return tracks
    }

    private fun resolveDeezer(kind: String, id: String): CatalogResult {
        val api = "https://api.deezer.com"
        return when (kind) {
            "track" -> {
                val obj = httpJson("$api/track/$id")
                checkDeezerError(obj)
                val title = obj.optString("title")
                val artist = obj.optJSONObject("artist")?.optString("name").orEmpty()
                CatalogResult(null, listOf(CatalogTrack(title, artist)))
            }
            "album" -> {
                val album = httpJson("$api/album/$id")
                checkDeezerError(album)
                val albumTitle = album.optString("title", id)
                val albumArtist = album.optJSONObject("artist")?.optString("name").orEmpty()
                val tracksObj = httpJson("$api/album/$id/tracks?limit=500")
                checkDeezerError(tracksObj)
                val tracks = parseDeezerTracks(tracksObj)
                val folder = listOf(albumArtist, albumTitle).filter { it.isNotBlank() }.joinToString(" - ")
                CatalogResult(folder.ifBlank { "Deezer $id" }, tracks)
            }
            else -> {
                val pl = httpJson("$api/playlist/$id")
                checkDeezerError(pl)
                val tracksObj = httpJson("$api/playlist/$id/tracks?limit=500")
                checkDeezerError(tracksObj)
                CatalogResult(pl.optString("title", "Deezer $id"), parseDeezerTracks(tracksObj))
            }
        }
    }

    private fun parseDeezerTracks(obj: JSONObject): List<CatalogTrack> {
        val arr = obj.optJSONArray("data") ?: return emptyList()
        val list = ArrayList<CatalogTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title")
            if (title.isBlank()) continue
            val artist = item.optJSONObject("artist")?.optString("name").orEmpty()
            list.add(CatalogTrack(title, artist))
        }
        return list
    }

    private fun checkDeezerError(obj: JSONObject) {
        if (obj.has("error")) {
            val msg = obj.optJSONObject("error")?.optString("message").orEmpty()
            throw IllegalStateException("Deezer API: ${msg.ifBlank { "ошибка доступа" }}")
        }
    }

    private fun resolveApple(urlStr: String): CatalogResult {
        val u = URL(urlStr)
        val parts = u.path.split('/').filter { it.isNotBlank() }.toMutableList()
        var cc = "us"
        if (parts.isNotEmpty() && parts[0].matches(Regex("[a-z]{2}"))) {
            cc = parts.removeAt(0)
        }
        val kind = parts.firstOrNull().orEmpty()
        val last = parts.lastOrNull()?.replace(Regex("""^id(?=\d)"""), "").orEmpty()
        if (kind == "playlist" || last.startsWith("pl.")) {
            throw IllegalStateException("Плейлисты Apple Music без входа в аккаунт недоступны")
        }
        val queryTrackId = u.query?.split('&')
            ?.firstOrNull { it.startsWith("i=") }
            ?.substringAfter("i=")
            ?: if (kind == "song") last else null

        val look = "https://itunes.apple.com/lookup?country=${URLEncoder.encode(cc, "UTF-8")}"
        if (!queryTrackId.isNullOrBlank() && queryTrackId.all { it.isDigit() }) {
            val res = httpJson("$look&id=$queryTrackId").optJSONArray("results")
            if (res != null) {
                for (i in 0 until res.length()) {
                    val item = res.optJSONObject(i) ?: continue
                    if (item.optString("wrapperType") == "track" && item.optString("trackName").isNotBlank()) {
                        return CatalogResult(
                            null,
                            listOf(CatalogTrack(item.optString("trackName"), item.optString("artistName")))
                        )
                    }
                }
            }
            throw IllegalStateException("Трек не найден в iTunes API")
        }

        if (kind == "album" && last.isNotBlank() && last.all { it.isDigit() }) {
            val res = httpJson("$look&id=$last&entity=song&limit=200").optJSONArray("results")
                ?: throw IllegalStateException("Альбом не найден в iTunes API")
            var artistName = ""
            var collectionName = ""
            val tracks = mutableListOf<CatalogTrack>()
            for (i in 0 until res.length()) {
                val item = res.optJSONObject(i) ?: continue
                when (item.optString("wrapperType")) {
                    "collection" -> {
                        artistName = item.optString("artistName")
                        collectionName = item.optString("collectionName")
                    }
                    "track" -> {
                        val name = item.optString("trackName")
                        if (name.isNotBlank()) {
                            tracks.add(CatalogTrack(name, item.optString("artistName")))
                        }
                    }
                }
            }
            if (tracks.isEmpty()) throw IllegalStateException("Альбом пуст или не найден")
            val folder = listOf(artistName, collectionName).filter { it.isNotBlank() }.joinToString(" - ")
            return CatalogResult(folder.ifBlank { "Apple Album $last" }, tracks)
        }
        throw IllegalArgumentException("Неподдерживаемая ссылка Apple Music")
    }

    private fun followRedirectsHttpOnly(startUrl: String): String {
        var current = startUrl
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", UA)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location").orEmpty()
                conn.disconnect()
                if (loc.startsWith("https://") || loc.startsWith("http://")) {
                    current = loc
                } else {
                    return current
                }
            } else {
                conn.disconnect()
                return current
            }
        }
        return current
    }

    private fun httpJson(urlStr: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }
    }
}
