package hr.workspace.boat4you.domains.contactcenter.service

/**
 * The bot flags that can be decided when a visit starts (design 2.6): a known crawler, preview fetcher or automation token in the
 * User-Agent, the generic `bot|crawler|spider/<digit>` shape, `navigator.webdriver`, and a 0-px viewport. The result is the
 * `cc_visit.bot_reason` (VARCHAR(40)), or null for a visitor that looks like a person. The page-view rate flag is decided later, per
 * beat, from [MAX_PAGE_VIEWS_PER_MINUTE]; the hosting-network flag by [DatacenterIpFilter].
 */
object BotFilter {
    /** More distinct page views than this within 60 s on one visit is not a person (design 2.6, impossible rate). */
    const val MAX_PAGE_VIEWS_PER_MINUTE = 30

    /** The `bot_reason` of a visit flagged for [MAX_PAGE_VIEWS_PER_MINUTE]. */
    const val PV_RATE = "pv_rate"

    private const val MAX_REASON = 40

    /** Lowercase substrings of the User-Agent, checked in this order; the first hit names the reason, so the order is part of the contract. */
    private val UA_TOKENS =
        listOf(
            "meta-externalagent",
            "meta-externalfetcher",
            "facebookexternalhit",
            "headlesschrome",
            "phantomjs",
            "puppeteer",
            "playwright",
            "selenium",
            "lighthouse",
            "pagespeed",
            "gtmetrix",
            "googlebot",
            "adsbot-google",
            "mediapartners-google",
            "google-inspectiontool",
            "googleother",
            "bingbot",
            "bingpreview",
            "yandex",
            "baiduspider",
            "duckduckbot",
            "applebot",
            "amazonbot",
            "petalbot",
            "bytespider",
            "gptbot",
            "chatgpt-user",
            "oai-searchbot",
            "claudebot",
            "claude-web",
            "anthropic-ai",
            "perplexitybot",
            "ccbot",
            "ahrefsbot",
            "semrushbot",
            "mj12bot",
            "dotbot",
            "uptimerobot",
            "pingdom",
            "statuscake",
            "curl/",
            "wget/",
            "python-requests",
            "python-urllib",
            "aiohttp",
            "go-http-client",
            "java/",
            "okhttp",
            "axios/",
            "node-fetch",
            "scrapy",
            "httpclient",
        )

    /** A crawler that is not on the list: `SomeBot/1.0`, or `compatible; ... Crawler` in the parenthesised part. */
    private val GENERIC = Regex("""(?i)\w*(?:bot|crawler|spider)/\d|compatible;[^)]*?\w*(?:bot|crawler|spider)\b""")

    /** Why this visit is a bot, or null. [viewportW] and [viewportH] are null for a client that did not report them. */
    fun atVisit(
        userAgent: String?,
        webdriver: Boolean,
        viewportW: Int?,
        viewportH: Int?,
    ): String? {
        if (userAgent.isNullOrBlank()) return "empty_ua"
        val ua = userAgent.lowercase()
        val token = UA_TOKENS.firstOrNull { it in ua }
        return when {
            token != null -> "ua:$token".take(MAX_REASON)
            GENERIC.containsMatchIn(userAgent) -> "ua:pattern"
            webdriver -> "webdriver"
            viewportW == 0 || viewportH == 0 -> "zero_viewport"
            else -> null
        }
    }
}
