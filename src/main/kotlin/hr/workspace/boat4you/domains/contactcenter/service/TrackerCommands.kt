package hr.workspace.boat4you.domains.contactcenter.service

/** The `utm_*` parameters of a landing URL, each already clipped; `null` when the URL carried none. */
data class Utm(
    val source: String? = null,
    val medium: String? = null,
    val campaign: String? = null,
    val term: String? = null,
    val content: String? = null,
)
