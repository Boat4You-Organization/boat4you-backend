package hr.workspace.boat4you.domains.pr.links

import hr.workspace.boat4you.domains.pr.jpa.PrLinkState
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/** A link to one of our sites: [href] absolute, [rel] lowercase tokens, [anchorText] the visible text. */
data class FoundLink(
    val href: String,
    val rel: Set<String>,
    val anchorText: String,
)

/**
 * Our links on a page, and the page-level robots meta: [metaNoindex] / [metaNofollow] come from
 * `<meta name="robots">` or `<meta name="googlebot">` (`none` sets both).
 */
data class LinkInspection(
    val links: List<FoundLink>,
    val metaNoindex: Boolean,
    val metaNofollow: Boolean = false,
)

/** Finds our links in an article (Jsoup) and turns a fetch plus that inspection into a PrLinkState. Pure functions. */
object PrLinkInspector {
    private val NOFOLLOW_REL = setOf("nofollow", "sponsored", "ugc")
    private val ROBOTS_META = setOf("robots", "googlebot")
    private val BLOCKED = setOf(-1, 401, 402, 403, 429, 503)
    private val GONE = setOf(404, 410)
    private val WHITESPACE = Regex("\\s+")
    private val ROBOTS_TOKENS = Regex("[\\s,:]+")
    private val HOST = Regex("^[a-z][a-z0-9+.-]*://(?:[^@/?#]*@)?(\\[[^]]*]|[^:/?#]*)", RegexOption.IGNORE_CASE)

    /**
     * Every `a[href]` whose host is one of [ourDomains] or a subdomain of one (`www.` included), case-insensitive.
     * Relative hrefs resolve against [baseUrl] (pass the fetch's finalUrl); without it they are skipped.
     */
    fun inspect(
        html: String,
        ourDomains: List<String>,
        baseUrl: String? = null,
    ): LinkInspection {
        val doc = Jsoup.parse(html, baseUrl.orEmpty())
        val domains = ourDomains.map { it.trim().lowercase(Locale.ROOT).trimEnd('.') }.filter { it.isNotEmpty() }
        val links =
            doc.select("a[href]").mapNotNull { a ->
                val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
                val host = hostOf(href) ?: return@mapNotNull null
                if (domains.none { host == it || host.endsWith(".$it") }) return@mapNotNull null
                val rel = a.attr("rel").lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }.toSet()
                FoundLink(href, rel, a.text().trim())
            }
        val robots =
            doc
                .select("meta[name][content]")
                .filter { it.attr("name").trim().lowercase(Locale.ROOT) in ROBOTS_META }
                .flatMap { robotsTokens(it.attr("content")) }
                .toSet()
        return LinkInspection(links, metaNoindex = "noindex" in robots || "none" in robots, metaNofollow = "nofollow" in robots || "none" in robots)
    }

    /**
     * -1 / 401 / 402 / 403 / 429 / 503 (the page refused us, or nothing was fetched) and any other non-2xx except
     * 404 / 410 -> BLOCKED_UNKNOWN; 404 / 410 -> PAGE_GONE; 2xx without our link -> LINK_MISSING; 2xx with at least one
     * followed link of ours on a page that is not noindex / nofollow (meta robots or X-Robots-Tag) -> OK_FOLLOW;
     * otherwise OK_NOFOLLOW.
     */
    fun classify(
        fetch: FetchResult,
        inspection: LinkInspection?,
    ): PrLinkState {
        val status = fetch.status
        return when {
            status in BLOCKED -> PrLinkState.BLOCKED_UNKNOWN
            status in GONE -> PrLinkState.PAGE_GONE
            status !in 200..299 -> PrLinkState.BLOCKED_UNKNOWN
            inspection == null || inspection.links.isEmpty() -> PrLinkState.LINK_MISSING
            else -> {
                val header = robotsTokens(fetch.xRobotsTag.orEmpty())
                val pageNofollow =
                    inspection.metaNoindex || inspection.metaNofollow || header.any { it == "nofollow" || it == "noindex" || it == "none" }
                val followed = inspection.links.any { link -> link.rel.none { it in NOFOLLOW_REL } }
                if (followed && !pageNofollow) PrLinkState.OK_FOLLOW else PrLinkState.OK_NOFOLLOW
            }
        }
    }

    private fun robotsTokens(value: String): List<String> = value.lowercase(Locale.ROOT).split(ROBOTS_TOKENS).filter { it.isNotEmpty() }

    /** Lowercase host without a trailing dot; null for mailto:, javascript: and anything else without one. */
    private fun hostOf(href: String): String? {
        val host = runCatching { URI(href).host }.getOrNull() ?: HOST.find(href)?.groupValues?.get(1)
        return host?.lowercase(Locale.ROOT)?.trimEnd('.')?.ifEmpty { null }
    }
}
