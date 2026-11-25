package io.github.doromere.downloader.domain

import java.net.URI

enum class VideoQuality(val label: String) {
    BEST("Лучшее"),
    Q1080("1080p"),
    Q720("720p"),
    AUDIO_ONLY("Аудио")
}

enum class AudioFormat(val ext: String) {
    MP3("mp3"),
    M4A("m4a"),
    OPUS("opus")
}

enum class DownloadKind {
    VIDEO,
    AUDIO,
    MUSIC_CATALOG,
    PLAYLIST_DUMP,
    DIRECT_FILE,
    DRM_BLOCKED,
    UNSUPPORTED_MUSIC,
    GENERIC
}

data class ServiceMatch(
    val url: String,
    val service: String,
    val kind: DownloadKind,
    val isMass: Boolean
)

object UrlRouter {
    private val schemeUrlRegex = Regex("""(https?://|magnet:\?)[^\s<>"]+""")
    private val bareDomainRegex = Regex("""(?:^|[\s(])((?:www\.)?(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}/[^\s<>"]*)""")
    private val normalizeBareRegex = Regex("""^[A-Za-z0-9][A-Za-z0-9.-]*\.[A-Za-z]{2,}(/[^\s]*)?$""")
    private val trailingPunctRegex = Regex("""[.,;!»"'’…]+$""")

    private val directExts = setOf(
        "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp",
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus",
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg",
        "pdf", "epub", "fb2", "djvu", "mobi",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "txt", "csv",
        "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2",
        "apk", "apks", "xapk", "exe", "msi", "iso", "deb"
    )

    private val drmDomains = setOf(
        "netflix.com", "disneyplus.com", "primevideo.com", "max.com", "hbomax.com",
        "tv.apple.com", "kinopoisk.ru", "hd.kinopoisk.ru", "ivi.ru", "okko.tv",
        "start.ru", "wink.ru", "more.tv", "kion.ru"
    )

    private val videoDomains = mapOf(
        "youtube.com" to "YouTube",
        "youtu.be" to "YouTube",
        "youtube-nocookie.com" to "YouTube",
        "vk.com" to "VK",
        "vk.ru" to "VK",
        "vkvideo.ru" to "VK",
        "vk.cc" to "VK",
        "ok.ru" to "OK",
        "rutube.ru" to "Rutube",
        "dzen.ru" to "Dzen",
        "zen.yandex.ru" to "Dzen",
        "facebook.com" to "Facebook",
        "fb.watch" to "Facebook",
        "fb.com" to "Facebook",
        "twitch.tv" to "Twitch",
        "vimeo.com" to "Vimeo",
        "dailymotion.com" to "Dailymotion",
        "dai.ly" to "Dailymotion",
        "bilibili.com" to "Bilibili",
        "bilibili.tv" to "Bilibili",
        "b23.tv" to "Bilibili",
        "coub.com" to "Coub",
        "tiktok.com" to "TikTok",
        "reddit.com" to "Reddit",
        "redd.it" to "Reddit",
        "t.me" to "Telegram",
        "telegram.me" to "Telegram",
        "x.com" to "Twitter",
        "twitter.com" to "Twitter",
        "fxtwitter.com" to "Twitter",
        "vxtwitter.com" to "Twitter",
        "fixupx.com" to "Twitter",
        "instagram.com" to "Instagram",
        "threads.net" to "Threads",
        "threads.com" to "Threads",
        "bsky.app" to "Bluesky",
        "tumblr.com" to "Tumblr"
    )

    private val audioDomains = mapOf(
        "music.youtube.com" to "YouTube Music",
        "soundcloud.com" to "SoundCloud",
        "bandcamp.com" to "Bandcamp",
        "mixcloud.com" to "Mixcloud"
    )

    fun cleanToken(raw: String): String {
        var u = trailingPunctRegex.replace(raw.trim(), "")
        while (u.endsWith(")") && u.count { it == ')' } > u.count { it == '(' }) {
            u = u.dropLast(1)
        }
        return trailingPunctRegex.replace(u, "")
    }

    fun normalizeUrl(raw: String): String? {
        val u = cleanToken(raw)
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u
        }
        if (normalizeBareRegex.matches(u)) {
            return "https://$u"
        }
        return null
    }

    fun extractUrls(text: String, limit: Int = 50): List<String> {
        val schemeMatches = schemeUrlRegex.findAll(text).map { it.value }.toList()
        val rawList = if (schemeMatches.isNotEmpty()) {
            schemeMatches
        } else {
            bareDomainRegex.findAll(text).map { it.groupValues[1] }.toList()
        }
        val out = LinkedHashSet<String>()
        for (item in rawList) {
            val cleaned = cleanToken(item)
            if (cleaned.isNotEmpty() && !cleaned.startsWith("magnet:")) {
                out.add(cleaned)
                if (out.size >= limit) break
            }
        }
        return out.toList()
    }

    fun isDirectFile(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return false
        val ext = last.substringAfterLast('.')
        return ext in directExts
    }

    fun isMass(url: String): Boolean {
        val low = url.lowercase()
        return low.contains("youtube.com/playlist") ||
            low.contains("youtube.com/@") ||
            low.contains("youtube.com/channel/") ||
            low.contains("youtube.com/c/") ||
            low.contains("youtube.com/user/") ||
            low.contains("open.spotify.com/artist/") ||
            Regex("""open\.spotify\.com/[^/]+/artist/""").containsMatchIn(low)
    }

    private fun matchHost(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    fun classify(rawUrl: String): ServiceMatch? {
        val url = normalizeUrl(rawUrl) ?: return null
        val parsedHost = try {
            URI(url).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val host = parsedHost.replace(Regex("""^(www|m|mobile)\."""), "")

        if (host.isEmpty()) return null

        if (drmDomains.any { matchHost(host, it) }) {
            return ServiceMatch(url, "DRM", DownloadKind.DRM_BLOCKED, false)
        }
        if (host == "deezer.com" || host.endsWith(".deezer.com") ||
            host in setOf("link.deezer.com", "deezer.page.link", "dzr.page.link")
        ) {
            return ServiceMatch(url, "Deezer", DownloadKind.MUSIC_CATALOG, url.contains("/album/") || url.contains("/playlist/"))
        }
        if (host in setOf("music.apple.com", "itunes.apple.com", "geo.music.apple.com")) {
            return ServiceMatch(url, "Apple Music", DownloadKind.MUSIC_CATALOG, url.contains("/album/"))
        }
        if (host == "spotify.link" || matchHost(host, "spotify.com") ||
            matchHost(host, "tidal.com") || host.startsWith("music.amazon.")
        ) {
            val svc = when {
                host.contains("spotify") -> "Spotify"
                host.contains("tidal") -> "Tidal"
                else -> "Amazon Music"
            }
            return ServiceMatch(url, svc, DownloadKind.UNSUPPORTED_MUSIC, isMass(url))
        }
        if (host.startsWith("music.yandex.")) {
            return ServiceMatch(url, "Yandex Music", DownloadKind.AUDIO, false)
        }
        audioDomains.entries.firstOrNull { matchHost(host, it.key) }?.let { (_, svc) ->
            return ServiceMatch(url, svc, DownloadKind.AUDIO, isMass(url))
        }
        if (isDirectFile(url)) {
            return ServiceMatch(url, "Files", DownloadKind.DIRECT_FILE, false)
        }
        videoDomains.entries.firstOrNull { matchHost(host, it.key) }?.let { (_, svc) ->
            return ServiceMatch(url, svc, DownloadKind.VIDEO, isMass(url))
        }
        return ServiceMatch(url, "Other", DownloadKind.GENERIC, isMass(url))
    }
}
