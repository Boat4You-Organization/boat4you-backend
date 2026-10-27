package hr.workspace.boat4you.domains.contactcenter.service

/**
 * The kind of page a visitor is on and, for a boat page, its slug, from the stored path alone. Every site has its own URL scheme,
 * so the segment that marks a boat page and a listing differs by site id. A leading locale segment (`/hr/boat/x`) is ignored.
 */
object PageTypeClassifier {
    /** [type] is one of HOME, BOAT, SEARCH, DESTINATION, BLOG, ITINERARY, BOOKING or OTHER; [boatSlug] is set only for BOAT. */
    data class PageType(
        val type: String,
        val boatSlug: String?,
    )

    private val LOCALE = Regex("^/(en|hr|fr|de|pt|it|es|pl|nl)(?=/|$)")
    private val SLUG = Regex("^[A-Za-z0-9-]{1,200}$") // BACKEND-15 slug charset
    private val BOAT = mapOf("boat4you" to setOf("boat"), "croatia-yachting" to setOf("yachts"), "europe-yachts" to setOf("yachts", "yacht"))
    private val LISTING =
        mapOf("boat4you" to setOf("search", "fleet"), "croatia-yachting" to setOf("yachts", "fleet"), "europe-yachts" to setOf("yachts", "fleet"))
    private val BOOKING = setOf("enter-your-details", "payment", "payment-success", "payment-pending", "payment-cancelled", "wizard", "inquiry", "request-a-quote")

    fun classify(
        siteId: String,
        pathAndQuery: String,
    ): PageType {
        val segs = LOCALE.replace(pathAndQuery.substringBefore('?'), "").split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return PageType("HOME", null)
        if (segs.size == 2 && segs[0] in BOAT.getOrDefault(siteId, setOf("catamarans")) && SLUG.matches(segs[1])) return PageType("BOAT", segs[1])
        val type =
            when (segs[0]) {
                in LISTING.getOrDefault(siteId, setOf("catamarans", "fleet")) -> "SEARCH"
                "destinations" -> "DESTINATION"
                "blog" -> "BLOG"
                "itinerary", "itineraries" -> "ITINERARY"
                in BOOKING -> "BOOKING"
                else -> "OTHER"
            }
        return PageType(type, null)
    }
}
