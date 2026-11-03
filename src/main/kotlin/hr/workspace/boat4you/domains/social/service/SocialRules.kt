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

    private fun xWeight(cp: Int) = if (cp in 0..4351 || cp in 8192..8205 || cp in 8208..8223 || cp in 8242..8247) 1 else 2

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
        val out = mutableListOf<String>()
        val images = media.count { it.kind == SocialMediaKind.IMAGE }
        val videos = media.count { it.kind == SocialMediaKind.VIDEO }
        if (text.isBlank() && media.isEmpty()) out += "nothing to publish"
        val chars = count(text, r.counting)
        if (chars > r.maxChars) out += "text too long ($chars/${r.maxChars})"
        if (r.videoRequired && videos == 0) {
            out += "needs a video"
        } else if (r.mediaRequired && media.isEmpty()) {
            out += "needs a photo or video"
        }
        if (images > 0 && r.maxImages == 0) {
            out += "photos are not supported"
        } else if (images > r.maxImages) {
            out += "at most ${r.maxImages} photos"
        }
        if (videos > 0 && r.maxVideos == 0) {
            out += "video is not supported"
        } else if (videos > r.maxVideos) {
            out += "at most ${r.maxVideos} video"
        }
        if (!r.allowMixed && images > 0 && videos > 0 && r.maxImages > 0 && r.maxVideos > 0) out += "photos and a video cannot go together"
        val t = title.orEmpty()
        if (r.titleRequired && t.isBlank()) out += "title required"
        if (r.maxTitle > 0 && t.codePointCount(0, t.length) > r.maxTitle) out += "title too long (max ${r.maxTitle})"
        if (platform == SocialPlatform.YOUTUBE && (t + text).any { it == '<' || it == '>' }) out += "YouTube refuses < and >"
        r.maxHashtags?.let { max -> hashtags(text).takeIf { it > max }?.let { out += "at most $max hashtags ($it)" } }
        if (platform == SocialPlatform.INSTAGRAM &&
            media.any { it.kind == SocialMediaKind.IMAGE && it.width != null && it.height != null && !instagramRatioOk(it.width, it.height) }
        ) {
            out += "Instagram photos must be between 4:5 and 1.91:1"
        }
        return out
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
    private const val IG_MIN_RATIO = 0.8
    private const val IG_MAX_RATIO = 1.91
    private const val RATIO_SLACK = 0.005
}
