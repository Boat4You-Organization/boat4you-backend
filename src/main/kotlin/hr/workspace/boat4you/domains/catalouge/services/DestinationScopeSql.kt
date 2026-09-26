package hr.workspace.boat4you.domains.catalouge.services

/**
 * The destination match of the native yacht_search_view queries (sidebar facets, relax suggestions), the SQL twin of
 * YachtQueryingService.buildYachtSearchPredicates, bound to `:marinaIds` / `:didCountryCodes` (26.9.2026 audit B14).
 */
internal object DestinationScopeSql {
    /** Pickup in scope: the offer starts at a marina of the destination / in its country. */
    fun pickup(
        hasMarinas: Boolean,
        hasCountries: Boolean,
    ): String =
        listOfNotNull(
            "location_from IN (:marinaIds)".takeIf { hasMarinas },
            "country_code IN (:didCountryCodes)".takeIf { hasCountries },
        ).joinToString(" OR ", "(", ")")

    /**
     * Undated searches only: the boat is based here - the row ends in scope (no drop-off, the same place or another
     * place of the destination) or the boat's home base (yacht.location_id) is in scope. A Kaštela boat's one-way
     * week from Dubrovnik back to Kaštela does not put it on the Dubrovnik landing.
     */
    fun basedHere(
        hasMarinas: Boolean,
        hasCountries: Boolean,
    ): String {
        val ends =
            listOfNotNull(
                "location_to IS NULL",
                "location_to IN (:marinaIds)".takeIf { hasMarinas },
                "country_code_to IN (:didCountryCodes)".takeIf { hasCountries },
            )
        val home =
            listOfNotNull(
                "hy.location_id IN (:marinaIds)".takeIf { hasMarinas },
                "hl.country_code IN (:didCountryCodes)".takeIf { hasCountries },
            ).joinToString(" OR ")
        val homes =
            "yacht_search_view.id IN (SELECT hy.id FROM yacht hy LEFT JOIN location hl ON hl.id = hy.location_id WHERE $home)"
        return (ends + homes).joinToString(" OR ", "(", ")")
    }
}
