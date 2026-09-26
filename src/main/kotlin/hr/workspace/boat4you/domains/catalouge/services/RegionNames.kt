package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.jpa.Region
import hr.workspace.boat4you.domains.catalouge.jpa.RegionAliasRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository

/**
 * Region names are CANONICAL (26.9.2026 audit B01): the web addresses a region landing by its name
 * (/search?destinations=zadar), and the URL, canonical, sitemap and index gate all derive from it. Both catalogue syncs
 * used to overwrite `region.name` on every run, and a region mapped by MMK and NauSys flipped between their spellings
 * ("Zadar" / "Zadar region") with whichever sync ran last - 144 sitemap URLs went noindex and back within 13 minutes.
 *
 * Now a sync:
 *  - finds the region of a partner area by its mapping, else by canonical name, else by any recorded spelling
 *    (region_alias) - so an old or other-partner spelling joins the existing region instead of forking a new one;
 *  - sets the name only on a region it creates;
 *  - records the partner's current spelling as an alias (never the name).
 */
object RegionNames {
    const val SOURCE_MMK = "MMK"
    const val SOURCE_NAUSYS = "NAUSYS"

    fun findOrNew(
        mapped: Region?,
        partnerName: String,
        regionRepository: RegionRepository,
    ): Region =
        mapped
            ?: regionRepository.findByName(partnerName)
            ?: regionRepository.findByAlias(partnerName)
            ?: Region()

    /** The partner's name becomes the canonical name only of a region that has none (a new one). */
    fun nameNewRegion(
        region: Region,
        partnerName: String?,
    ) {
        if (region.name.isNullOrBlank()) region.name = partnerName?.trim()
    }

    /** Keep the partner's spelling resolvable (alias) - call after the region is saved. */
    fun recordSpelling(
        region: Region,
        partnerName: String?,
        source: String,
        regionAliasRepository: RegionAliasRepository,
    ) {
        val id = region.id ?: return
        val name = partnerName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        regionAliasRepository.record(id, name.take(MAX_ALIAS_LENGTH), source)
    }

    private const val MAX_ALIAS_LENGTH = 100
}
