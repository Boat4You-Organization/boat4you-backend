package hr.workspace.boat4you.domains.catalouge.services

/**
 * The `sortBy` values the listing query (YachtQueryingService.searchYachts) orders by. Every other value — "", "recommended",
 * "recommendedScore", unknown junk — produces the Recommended ORDER BY, so [normalize] maps it to null BEFORE the call:
 * `sortBy` is part of the yachtSearchListCache key, and a raw string let `?sortBy=<random>` create a cold full listing
 * query per value (re-audit R12, 14.10.2026). The `when` in searchYachts reads these constants, so the list cannot drift.
 */
object SearchSort {
    const val ASC = "asc"
    const val DESC = "desc"
    const val LOWEST_PREPAYMENT = "lowestPrepayment"
    const val DISCOUNT = "discount"
    const val LENGTH_ASC = "lengthAsc"
    const val LENGTH_DESC = "lengthDesc"
    const val ID = "id"
    const val ID_DESC = "idDesc"

    val KNOWN: Set<String> = setOf(ASC, DESC, LOWEST_PREPAYMENT, DISCOUNT, LENGTH_ASC, LENGTH_DESC, ID, ID_DESC)

    /** A known sort unchanged; anything else (null, "", "recommended", junk) → null = the Recommended order. */
    fun normalize(sortBy: String?): String? = sortBy?.takeIf { it in KNOWN }
}
