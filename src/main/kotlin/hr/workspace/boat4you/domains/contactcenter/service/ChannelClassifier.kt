package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import org.springframework.stereotype.Component

/**
 * Where a visit came from, decided once when it starts (design 2.2). The first matching rule wins: a sister-site handoff, a
 * `cc_src` marker, an ad click, e-mail, social, a referrer that is a sister site or the own site, a search engine, web mail, any
 * other campaign tag, any other referrer, and last direct.
 */
@Component
class ChannelClassifier(
    private val urls: TrackerUrlPolicy,
) {
    /** [channel] is one of SISTER, ADS, EMAIL, SOCIAL, DIRECT, ORGANIC, OTHER or REFERRAL; [fromSiteId] is set only for SISTER. */
    data class Attribution(
        val channel: String,
        val fromSiteId: String?,
    )

    /**
     * [referrerHost] is the lower-case host of the clean referrer. [landingParams] are the names of all query parameters on the landing
     * URL. [handoffFromSiteId] is the site that issued a valid handoff token. [ccSrc] is the `cc_src=<siteId>` value of the landing URL
     * (the AI's "book now" links, P1c).
     */
    @Suppress("LongParameterList")
    fun classify(
        siteId: String,
        referrerHost: String?,
        utm: Utm,
        gclid: String?,
        fbclid: String?,
        landingParams: Set<String>,
        handoffFromSiteId: String?,
        ccSrc: String? = null,
    ): Attribution {
        val medium = utm.medium?.lowercase()
        val source = utm.source?.lowercase()
        val refSite = referrerHost?.let(urls::siteOfHost)
        return when {
            handoffFromSiteId != null -> Attribution("SISTER", handoffFromSiteId)
            ccSrc != null && ccSrc != siteId && SiteIds.isKnown(ccSrc) -> Attribution("SISTER", ccSrc)
            gclid != null || landingParams.any { it in AD_CLICK_PARAMS } || medium in AD_MEDIUMS -> Attribution("ADS", null)
            medium in EMAIL_MEDIUMS -> Attribution("EMAIL", null)
            medium in SOCIAL_MEDIUMS || fbclid != null || (referrerHost != null && SOCIAL_HOSTS.containsMatchIn(referrerHost)) ->
                Attribution("SOCIAL", null)
            refSite != null && refSite != siteId -> Attribution("SISTER", refSite)
            refSite == siteId -> Attribution("DIRECT", null)
            referrerHost != null && SEARCH_HOSTS.containsMatchIn(referrerHost) -> Attribution("ORGANIC", null)
            referrerHost != null && WEBMAIL_HOSTS.matches(referrerHost) -> Attribution("EMAIL", null)
            source != null || medium != null || utm.campaign != null -> Attribution("OTHER", null)
            referrerHost != null -> Attribution("REFERRAL", null)
            else -> Attribution("DIRECT", null)
        }
    }

    companion object {
        private val AD_CLICK_PARAMS = setOf("gclid", "gbraid", "wbraid", "msclkid", "dclid")
        private val AD_MEDIUMS = setOf("cpc", "ppc", "paid", "paidsearch", "paid_search", "paid-social", "paid_social", "display", "cpm", "cpv")
        private val EMAIL_MEDIUMS = setOf("email", "e-mail", "newsletter")
        private val SOCIAL_MEDIUMS = setOf("social", "social-network", "social_media", "sm")
        private val SOCIAL_HOSTS =
            Regex(
                """(^|\.)(facebook\.com|fb\.com|instagram\.com|t\.co|twitter\.com|x\.com|linkedin\.com|lnkd\.in|youtube\.com|tiktok\.com|reddit\.com)$""" +
                    """|(^|\.)pinterest\.[a-z.]+$""",
            )
        private val SEARCH_HOSTS =
            Regex(
                """^(www\.)?google\.[a-z.]+$|(^|\.)(bing\.com|duckduckgo\.com|search\.yahoo\.com|ecosia\.org|baidu\.com|startpage\.com|qwant\.com)$""" +
                    """|(^|\.)yandex\.[a-z.]+$|^search\.brave\.com$""",
            )
        private val WEBMAIL_HOSTS =
            Regex("""^(mail\.google\.com|outlook\.live\.com|outlook\.office\.com|outlook\.office365\.com|mail\.yahoo\.com)$""")
    }
}
