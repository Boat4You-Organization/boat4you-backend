package hr.workspace.boat4you.domains.social.service

import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import java.text.BreakIterator

/**
 * What each network refuses in one post, the same numbers as the admin's `src/utils/social/rules.ts` (checked against
 * the official publishing docs 3.11.2026). The admin warns while typing; this is the last check before a post can be
 * scheduled or published, so a post the network would refuse never waits in the queue. Only errors live here.
 */
object SocialRules {
    data class Rule(
        val maxChars: Int,
        val counting: Counting,
        val mediaRequired: Boolean,
        val videoRequired: Boolean,
        val maxImages: Int,
        val maxVideos: Int,
        val allowMixed: Boolean,
        val titleRequired: Boolean,
        val maxTitle: Int,
        val maxHashtags: Int?,
    )

    enum class Counting { CODEPOINTS, GRAPHEMES, X_WEIGHTED }

    data class MediaInfo(
        val kind: SocialMediaKind,
        val width: Int? = null,
        val height: Int? = null,
    )

    private val C = Counting.CODEPOINTS

    val RULES: Map<SocialPlatform, Rule> =
        mapOf(
            SocialPlatform.FACEBOOK to Rule(63206, C, false, false, 10, 1, false, false, 0, null),
            SocialPlatform.INSTAGRAM to Rule(2200, C, true, false, 10, 10, true, false, 0, 30),
            SocialPlatform.THREADS to Rule(500, C, false, false, 20, 20, true, false, 0, null),
            SocialPlatform.LINKEDIN to Rule(3000, C, false, false, 20, 1, false, false, 0, null),
            SocialPlatform.X to Rule(280, Counting.X_WEIGHTED, false, false, 4, 1, false, false, 0, null),
            SocialPlatform.TIKTOK to Rule(2200, C, true, false, 35, 1, false, false, 90, null),
            SocialPlatform.YOUTUBE to Rule(5000, C, true, true, 0, 1, false, true, 100, null),
            SocialPlatform.PINTEREST to Rule(500, C, true, false, 1, 1, false, false, 100, null),
            SocialPlatform.BLUESKY to Rule(300, Counting.GRAPHEMES, false, false, 4, 1, false, false, 0, null),
            SocialPlatform.GOOGLE_BUSINESS to Rule(1500, C, false, false, 1, 0, false, false, 0, null),
        )

    private val URL = Regex("""\bhttps?://[^\s<>"]+|\bwww\.[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val HASHTAG = Regex("""(^|\s)#[\p{L}\p{N}_]+""")

    /** twitter-text v3: these code points weigh 1, everything else (CJK, emoji) 2. */
    private val X_SINGLE_WEIGHT = listOf(0..4351, 8192..8205, 8208..8223, 8242..8247)

    private fun xWeight(cp: Int) = if (X_SINGLE_WEIGHT.any { cp in it }) 1 else 2

    fun count(
        text: String,
        counting: Counting,
    ): Int =
        when (counting) {
            Counting.CODEPOINTS -> text.codePointCount(0, text.length)
            Counting.GRAPHEMES -> {
                val it = BreakIterator.getCharacterInstance().apply { setText(text) }
                var n = 0
                while (it.next() != BreakIterator.DONE) n++
                n
            }
            Counting.X_WEIGHTED -> {
                var total = 0
                val rest =
                    URL.replace(text) {
                        total += X_URL_WEIGHT
                        ""
                    }
                rest.codePoints().forEach { total += xWeight(it) }
                total
            }
        }

    fun hashtags(text: String): Int = HASHTAG.findAll(text).count()

    /** Every reason the network would refuse this text / title / media; empty = fine. */
    fun errors(
        platform: SocialPlatform,
        text: String,
        title: String?,
        media: List<MediaInfo>,
    ): List<String> {
        val r = RULES.getValue(platform)
        return textErrors(r, text, media) + mediaErrors(r, media) + titleErrors(platform, r, text, title) + instagramErrors(platform, media)
    }

    private fun textErrors(
        r: Rule,
        text: String,
        media: List<MediaInfo>,
    ): List<String> {
        val out = mutableListOf<String>()
        if (text.isBlank() && media.isEmpty()) out += "nothing to publish"
        val chars = count(text, r.counting)
        if (chars > r.maxChars) out += "text too long ($chars/${r.maxChars})"
        r.maxHashtags?.let { max -> hashtags(text).takeIf { it > max }?.let { out += "at most $max hashtags ($it)" } }
        return out
    }

    private fun mediaErrors(
        r: Rule,
        media: List<MediaInfo>,
    ): List<String> {
        val images = media.count { it.kind == SocialMediaKind.IMAGE }
        val videos = media.count { it.kind == SocialMediaKind.VIDEO }
        val out = mutableListOf<String>()
        if (r.videoRequired && videos == 0) {
            out += "needs a video"
        } else if (r.mediaRequired && media.isEmpty()) {
            out += "needs a photo or video"
        }
        out += countErrors(images, r.maxImages, "photos")
        out += countErrors(videos, r.maxVideos, "video")
        val mixed = images > 0 && videos > 0
        val bothAllowed = r.maxImages > 0 && r.maxVideos > 0
        if (!r.allowMixed && mixed && bothAllowed) out += "photos and a video cannot go together"
        return out
    }

    private fun countErrors(
        n: Int,
        max: Int,
        what: String,
    ): List<String> =
        when {
            n > 0 && max == 0 -> listOf("$what ${if (what == "video") "is" else "are"} not supported")
            n > max -> listOf("at most $max $what")
            else -> emptyList()
        }

    private fun titleErrors(
        platform: SocialPlatform,
        r: Rule,
        text: String,
        title: String?,
    ): List<String> {
        val out = mutableListOf<String>()
        val t = title.orEmpty()
        if (r.titleRequired && t.isBlank()) out += "title required"
        if (r.maxTitle > 0 && t.codePointCount(0, t.length) > r.maxTitle) out += "title too long (max ${r.maxTitle})"
        if (platform == SocialPlatform.YOUTUBE && (t + text).any { it == '<' || it == '>' }) out += "YouTube refuses < and >"
        return out
    }

    private fun instagramErrors(
        platform: SocialPlatform,
        media: List<MediaInfo>,
    ): List<String> = if (platform == SocialPlatform.INSTAGRAM && media.any { badInstagramRatio(it) }) listOf("Instagram photos must be between 4:5 and 1.91:1") else emptyList()

    private fun badInstagramRatio(m: MediaInfo): Boolean {
        val (w, h) = m.width to m.height
        return m.kind == SocialMediaKind.IMAGE && w != null && h != null && !instagramRatioOk(w, h)
    }

    fun instagramRatioOk(
        width: Int,
        height: Int,
    ): Boolean {
        if (height <= 0) return false
        val ratio = width.toDouble() / height
        return ratio >= IG_MIN_RATIO - RATIO_SLACK && ratio <= IG_MAX_RATIO + RATIO_SLACK
    }

    private const val X_URL_WEIGHT = 23
    const val IG_MIN_RATIO = 0.8
    const val IG_MAX_RATIO = 1.91
    private const val RATIO_SLACK = 0.005
}
