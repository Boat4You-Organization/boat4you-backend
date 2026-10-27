package hr.workspace.boat4you.domains.contactcenter.service

import java.time.LocalDate

/**
 * The validated criteria of one yacht search, as `search_yachts` actually sent them to our own API (after the B8
 * whitelist), not as the model typed them. A null field means the criterion was not set, or was refused by the whitelist.
 *
 * [equipment] holds only the labels that resolved to an amenity id, so it is empty when the search never ran.
 * [resultCount] is the number of boats shown (0 on an error), [totalAvailable] the total the API reported (null on an
 * error) and [searchedArea] the place the API was asked about ("Split (region)"), null when none was resolved.
 */
data class SearchCriteria(
    val countryCode: String?,
    val location: String?,
    val did: Long?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val minPersons: Int?,
    val minCabins: Int?,
    val minPriceEur: Int?,
    val maxPriceEur: Int?,
    val vesselType: String?,
    val equipment: List<String>,
    val resultCount: Int,
    val totalAvailable: Int?,
    val searchedArea: String?,
) {
    companion object {
        /** The dates a stored search may carry: `LocalDate.parse` accepts years up to 999999999, PostgreSQL `DATE` does not. */
        private val EARLIEST = LocalDate.of(2000, 1, 1)
        private val LATEST = LocalDate.of(2100, 12, 31)

        /**
         * [raw] as an ISO date, or null when it does not parse or lies outside 2000-01-01..2100-12-31. A refused
         * `cc_search` insert would roll back the transaction that also stores the visitor's reply, so no date reaches the
         * row unless it passes through here.
         */
        internal fun parseDate(raw: String?): LocalDate? =
            raw
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.takeUnless { it.isBefore(EARLIEST) || it.isAfter(LATEST) }
    }
}
