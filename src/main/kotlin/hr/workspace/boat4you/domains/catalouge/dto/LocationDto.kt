package hr.workspace.boat4you.domains.catalouge.dto

data class LocationDto(
    val id: String?,
    val name: String?,
    val countryCode: String?,
    /**
     * The marina's coordinates (WGS 84), null when the catalogue has none (a custom boat's country, a base the partners
     * never located). The web points its map here instead of a text search on [name] ("Marina Kaštela | Kaštel
     * Gomilica"), which found no marina (Mario 13.10.2026). Additive: the same object on the boat page, its offers and
     * the search cards, on all 7 sites.
     */
    val lat: Double? = null,
    val lon: Double? = null,
)

/** A location's usable coordinates (see [coordinatesOf]); the search cards look them up per page by location id. */
data class LocationCoordinates(
    val lat: Double,
    val lon: Double,
)
