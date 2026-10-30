package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLDecoder

/** The outcome of [TrackerUrlPolicy.clean]: a rebuilt URL, or the reason the page is not stored. */
sealed interface UrlCheck {
    data class Ok(
        val url: CleanUrl,
    ) : UrlCheck

    /** The host is not a host of the claimed site. */
    data object ForeignHost : UrlCheck

    /** A token page (WEB-18): never stored. */
    data object Secret : UrlCheck

    /** Not an https URL of the right shape, or the path does not pass [TrackerUrlPolicy.SAFE_PAGE]. */
    data object Invalid : UrlCheck
}

/** [url] is rebuilt from the brand's own site URL, [path] is its path and query, [params] are all query values, decoded and clipped. */
data class CleanUrl(
    val url: String,
    val path: String,
    val params: Map<String, String>,
)

data class CleanReferrer(
    val url: String,
    val host: String,
)

/**
 * What the tracker accepts as a page of a site: the host must be a host of the claimed site, the stored URL is rebuilt from
 * [BrandRegistry] (never echoed), tracking parameters are stripped from the path, and token pages are dropped.
 * [devOriginHostsCsv] lists extra hosts (such as `localhost`) accepted as origins and page hosts on a development machine.
 */
@Component
class TrackerUrlPolicy(
    private val brands: BrandRegistry,
    @Value("\${CC_TRACKER_DEV_ORIGIN_HOSTS:}") devOriginHostsCsv: String,
) {
    private val devHosts: Set<String> = devOriginHostsCsv.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** The site id for a request host, or null (also for a null host). */
    fun siteOfHost(host: String?): String? = host?.let(SiteIds::fromHost)

    fun isDevHost(host: String?): Boolean = host != null && host.trim().lowercase() in devHosts

    /** True when the `Origin` header names a host of [siteId] (or a development host). A missing, `null` or unparsable origin is no match. */
    fun originMatches(
        origin: String?,
        siteId: String,
    ): Boolean {
        val host = origin?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase() ?: return false
        return isDevHost(host) || siteOfHost(host) == siteId
    }

    /** The page [raw] on [siteId]: see [UrlCheck]. */
    fun clean(
        siteId: String,
        raw: String?,
    ): UrlCheck {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL } ?: return UrlCheck.Invalid
        val uri = runCatching { URI(s) }.getOrNull() ?: return UrlCheck.Invalid
        val host = uri.host?.lowercase() ?: return UrlCheck.Invalid
        val dev = isDevHost(host)
        if (uri.rawUserInfo != null || !(uri.scheme == "https" || (dev && uri.scheme == "http"))) return UrlCheck.Invalid
        if (!dev && siteOfHost(host) != siteId) return UrlCheck.ForeignHost
        val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
        val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to it.substringAfter('=', "") }
        val decodedPath = uri.path.orEmpty() // the path as the page's server routes it: /hr/%72eview is /hr/review, /review%2Fabc is /review/abc
        if (SECRET_PATH.containsMatchIn(path) || SECRET_PATH.containsMatchIn(decodedPath) || pairs.any { isSecretParam(it.first) }) return UrlCheck.Secret
        val kept = pairs.filter { it.first !in STRIPPED_PARAMS }.joinToString("&") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }
        val pathAndQuery = if (kept.isEmpty() || path.length + 1 + kept.length > MAX_PATH) path else "$path?$kept"
        if (!SAFE_PAGE.matches(pathAndQuery)) return UrlCheck.Invalid
        val base = brands.get(siteId).websiteUrl
        val rebuilt = runCatching { URI(base + pathAndQuery) }.getOrNull() ?: return UrlCheck.Invalid
        if (rebuilt.host != URI(base).host || rebuilt.rawUserInfo != null) return UrlCheck.Invalid // host re-checked after parsing (B6)
        val params =
            pairs.associate { (k, v) ->
                k to (TrackerText.clip(runCatching { URLDecoder.decode(v, Charsets.UTF_8) }.getOrDefault(v), 200) ?: "")
            }
        return UrlCheck.Ok(CleanUrl(rebuilt.toString(), pathAndQuery, params))
    }

    /**
     * The referrer as plain bounded text, or null when [AiChatService.safeReferrer] refuses it. A same-origin referrer carries the
     * full URL, so a token page keeps only its origin (WEB-18).
     */
    fun referrer(raw: String?): CleanReferrer? {
        val r = raw?.let(AiChatService::safeReferrer) ?: return null
        val u = URI(r)
        val host = u.host.lowercase()
        val isTokenPage =
            SECRET_PATH.containsMatchIn(u.rawPath.orEmpty()) ||
                SECRET_PATH.containsMatchIn(u.path.orEmpty()) ||
                u.rawQuery.orEmpty().split('&').any { isSecretParam(it.substringBefore('=')) }
        return if (isTokenPage) CleanReferrer("${u.scheme}://$host/", host) else CleanReferrer(r, host)
    }

    /**
     * WEB-18 on what the browser routes to: a query parameter name as the page's server reads it, `to%6Ben` and `%74oken` are `token`.
     * `URI(...)` has already refused a malformed %-escape, so decoding cannot throw here; a name that still does not decode is tested as sent.
     */
    private fun isSecretParam(rawName: String): Boolean =
        rawName in SECRET_PARAMS || runCatching { URLDecoder.decode(rawName, Charsets.UTF_8) }.getOrDefault(rawName) in SECRET_PARAMS

    companion object {
        /** Copied character for character from the private `SAFE_PAGE` of `AiChatService` (BACKEND-25); a test keeps the two in step. */
        val SAFE_PAGE = Regex("""^/(?![/\\])[A-Za-z0-9/_\-.~%?=&+,]{0,499}$""")

        /** The query parameter that carries a handoff token between two sites. */
        const val HANDOFF_PARAM = "cch"

        /**
         * Parameters read for attribution but never stored in the path. `cc_src=<siteId>` is the source marker that P1c puts on the AI's
         * boat4you "book now" links.
         */
        val STRIPPED_PARAMS =
            setOf(
                "cch",
                "cc_src",
                "_gl",
                "gclid",
                "fbclid",
                "gbraid",
                "wbraid",
                "msclkid",
                "dclid",
                "utm_source",
                "utm_medium",
                "utm_campaign",
                "utm_term",
                "utm_content",
            )

        /** Pages whose URL is a credential (WEB-18). */
        val SECRET_PATH = Regex("^(/[a-z]{2})?/(review|unsubscribe|custom-offers|trip|confirm-email-change|forgot-password)(/|$)")
        val SECRET_PARAMS = setOf("token", "passwordResetCode")

        const val MAX_URL = 1000
        const val MAX_PATH = 500
    }
}
