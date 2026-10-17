package hr.workspace.boat4you.domains.catalouge.utils

import java.net.URI
import java.util.Locale

/**
 * The film URLs an admin may give a boat (V9_92, PATCH /admin/yachts/{id}/media): the forms the sites know how to embed.
 * - YouTube: `youtube.com/watch?v={id}`, `youtu.be/{id}`, `youtube.com/embed/{id}` (an 11-character video id);
 * - Vimeo: `vimeo.com/{id}` (an unlisted film's `vimeo.com/{id}/{hash}` too), `player.vimeo.com/video/{id}`.
 * http or https, with or without `www.`, no user info or port; any other URL is refused (400), so nothing but a video of
 * these two hosts ever reaches a page as an embed.
 */
object VideoUrlRules {
    const val MAX_LENGTH = 512

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    private val YOUTUBE_EMBED_PATH = Regex("/embed/[A-Za-z0-9_-]{11}/?")
    private val YOUTU_BE_PATH = Regex("/[A-Za-z0-9_-]{11}/?")
    private val VIMEO_PATH = Regex("/\\d+(/[0-9a-fA-F]+)?/?")
    private val VIMEO_PLAYER_PATH = Regex("/video/\\d+/?")

    fun isAccepted(url: String): Boolean {
        val uri = url.takeIf { it.length <= MAX_LENGTH && it.none(Char::isWhitespace) }?.let { runCatching { URI(it) }.getOrNull() }
        return uri != null &&
            uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            uri.rawUserInfo == null &&
            uri.port == -1 &&
            isVideoPath(uri.host?.lowercase(Locale.ROOT), uri.rawPath.orEmpty(), uri.rawQuery)
    }

    private fun isVideoPath(
        host: String?,
        path: String,
        rawQuery: String?,
    ): Boolean =
        when (host) {
            "youtube.com", "www.youtube.com" ->
                (path == "/watch" && queryParam(rawQuery, "v")?.let(YOUTUBE_ID::matches) == true) || YOUTUBE_EMBED_PATH.matches(path)
            "youtu.be" -> YOUTU_BE_PATH.matches(path)
            "vimeo.com", "www.vimeo.com" -> VIMEO_PATH.matches(path)
            "player.vimeo.com" -> VIMEO_PLAYER_PATH.matches(path)
            else -> false
        }

    private fun queryParam(
        rawQuery: String?,
        name: String,
    ): String? =
        rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
}
