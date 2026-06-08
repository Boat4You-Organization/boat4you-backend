package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.LocationCountDto
import hr.workspace.boat4you.domains.catalouge.dto.LocationViewDto
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.jpa.AllLocationViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtLocationsViewRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.jvm.optionals.getOrNull

@Service
@Transactional(readOnly = true)
class LocationQueryingService(
    private val locationRepository: LocationRepository,
    private val locationViewRepository: LocationViewRepository,
    private val allLocationViewRepository: AllLocationViewRepository,
    private val yachtLocationsViewRepository: YachtLocationsViewRepository,
) {
    fun getLocationById(id: Long): Location? {
        return locationRepository.findById(id).getOrNull()
    }

    fun getLocationByNameIgnoreCase(name: String): Location? {
        return locationRepository.findByNameIgnoreCase(name)
    }

    fun getLocationByExternalIdAndExternalSystemId(
        externalId: Long,
        externalSystemId: Long,
    ): Location? {
        return locationRepository.findByExternalIdAndExternalSystemId(externalId, externalSystemId)
    }

    @Cacheable(value = ["locationCache"], unless = "#result == null")
    fun getCachedLocationById(id: Long): Location? {
        return locationRepository.findById(id).getOrNull()
    }

    @Cacheable("locationViewsCache")
    fun getLocationViews(
        name: String?,
        selectedLocations: List<String>?,
        pageable: Pageable,
    ): Page<LocationViewDto> {
        val preselectedItems =
            if (selectedLocations.isNullOrEmpty()) {
                emptyList()
            } else {
                locationViewRepository.findByIds(selectedLocations).map { it.toLocationViewDto() }
            }

        val remainingPageSize = pageable.pageSize - preselectedItems.size

        return if (remainingPageSize > 0) {
            // Get other items excluding preselected ones
            val otherItemsPageable =
                PageRequest.of(
                    pageable.pageNumber,
                    remainingPageSize,
                    pageable.sort,
                )

            val otherItems =
                if (name.isNullOrBlank()) {
                    locationViewRepository
                        .findAllAndIdsNotIn(
                            selectedLocations,
                            otherItemsPageable,
                        ).map { it.toLocationViewDto() }
                } else {
                    locationViewRepository
                        .findByNameAndIdsNotIn(
                            name,
                            selectedLocations,
                            otherItemsPageable,
                        ).map { it.toLocationViewDto() }
                }
            // Combine preselected + other items
            val combinedItems = mergeDualSourceMarinas(preselectedItems + otherItems.content)
            val totalElements = preselectedItems.size + otherItems.totalElements

            PageImpl(combinedItems, pageable, totalElements)
        } else {
            // Page size is smaller than preselected items
            val pageItems = mergeDualSourceMarinas(preselectedItems.take(pageable.pageSize))
            val totalElements = locationViewRepository.count()

            PageImpl(pageItems, pageable, totalElements)
        }
    }

    /**
     * Collapse dual-source MARINA duplicates in the autocomplete: the same
     * physical marina imported once per partner under different names — e.g.
     * MMK "Marina Baotić" (l-1749) + NauSys "Trogir, Yachtclub Seget (Marina
     * Baotić)" (l-57). They show as two typeahead rows and split the fleet
     * across two chips. We keep ONE row (the longer, more descriptive name)
     * whose id carries BOTH location ids ("l-57,l-1749"); the FE's
     * useQueryParams splits `did` on comma so search hits BOTH providers and
     * clearing the chip wipes both. Durable here (search-time) — a DB merge gets
     * reverted by the catalogue sync.
     *
     * Match rule (deliberately conservative): a MARINA whose folded name is a
     * proper substring of EXACTLY ONE other MARINA's folded name in the same
     * country, within this result set. The "exactly one" guard keeps a city
     * name that is a substring of several distinct marinas ("Ibiza" ⊂ "Marina
     * Ibiza" / "Club Nautico Ibiza" / "Port of Ibiza") from collapsing
     * unrelated marinas — a name-filtered autocomplete query pulls all of a
     * city's marinas into the same page, so such a name matches >1 and is left
     * alone.
     */
    private fun mergeDualSourceMarinas(items: List<LocationViewDto>): List<LocationViewDto> {
        fun fold(s: String?): String = (s ?: "").lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

        val marinas = items.filter { it.locationType == LocationType.MARINA && it.id != null && fold(it.name).length >= 4 }
        if (marinas.size < 2) return items

        val removedIds = mutableSetOf<String>()
        val replacements = mutableMapOf<String, LocationViewDto>()

        for (shorter in marinas) {
            val shorterId = shorter.id ?: continue
            val fs = fold(shorter.name)
            val containers =
                marinas.filter { longer ->
                    longer.id != shorter.id &&
                        longer.countryCode == shorter.countryCode &&
                        fold(longer.name).length > fs.length &&
                        fold(longer.name).contains(fs)
                }
            if (containers.size != 1) continue
            val longer = containers.first()
            val longerId = longer.id ?: continue
            // Skip chained nestings (A ⊂ B ⊂ C) — only collapse a leaf shorter
            // into a row that is not itself being folded away.
            if (longerId in removedIds || shorterId in replacements.keys) continue
            val current = replacements[longerId] ?: longer
            replacements[longerId] = current.copy(id = "${current.id},$shorterId")
            removedIds.add(shorterId)
        }
        if (replacements.isEmpty()) return items

        return items.mapNotNull { item ->
            val iid = item.id
            when {
                iid == null -> item
                iid in removedIds -> null
                replacements.containsKey(iid) -> replacements[iid]
                else -> item
            }
        }
    }

    fun getAllCountries(
        name: String?,
        pageable: Pageable,
    ): Page<LocationViewDto> {
        return if (name.isNullOrBlank()) {
            allLocationViewRepository
                .findAllByLocationType(LocationType.COUNTRY, pageable)
                .map { it.toLocationViewDto() }
        } else {
            allLocationViewRepository
                .findAllByNameLikeAndLocationType(name, LocationType.COUNTRY, pageable)
                .map { it.toLocationViewDto() }
        }
    }

    @Cacheable("countriesCache")
    fun getCountries(): List<LocationViewDto> {
        return locationViewRepository.getCountries().map { it.toLocationViewDto() }
    }

    @Cacheable("regionsCache")
    fun getRegions(countryCode: String): List<LocationViewDto> {
        return locationViewRepository.getRegions(countryCode).map { it.toLocationViewDto() }
    }

    @Cacheable("marinasByCountryCache")
    fun getMarinas(countryCode: String): List<LocationViewDto> {
        return locationViewRepository.getMarinas(countryCode).map { it.toLocationViewDto() }
    }

    @Cacheable("countriesCountCache")
    fun getCountriesCount(): List<LocationCountDto> {
        return yachtLocationsViewRepository.getCountriesCount().map { row ->
            LocationCountDto(
                id = "c-" + (row[0] as Integer).toString(),
                countryCode = row[1] as String,
                yachtCount = (row[2] as Number).toInt(),
                name = row[3] as String,
                continent = row[4] as String,
            )
        }
    }

    @Cacheable("locationsCountCache")
    fun getLocationsCount(): List<LocationCountDto> {
        return yachtLocationsViewRepository.getLocationsCount().map { row ->
            LocationCountDto(
                id = "l-" + (row[0] as Long).toString(),
                countryCode = row[1] as String,
                yachtCount = (row[2] as Number).toInt(),
                name = row[3] as String,
                continent = row[4] as String,
            )
        }
    }

    /**
     * Locations (with yacht counts) restricted to a single region.
     * Powers the "Most popular destinations in {region}" internal-link
     * block — without this filter the block would have to dump all of
     * Croatia's marinas instead of just the ones in Split region.
     *
     * Cached per region — the underlying view is rebuilt on yacht sync
     * (~daily) so a 5-min cache is safe and saves a DB round-trip on
     * every search-page render.
     */
    @Cacheable(value = ["locationsCountByRegionCache"])
    fun getLocationsCountByRegion(regionId: Long): List<LocationCountDto> {
        return yachtLocationsViewRepository.getLocationsCountByRegion(regionId).map { row ->
            LocationCountDto(
                id = "l-" + (row[0] as Long).toString(),
                countryCode = row[1] as String,
                yachtCount = (row[2] as Number).toInt(),
                name = row[3] as String,
                continent = row[4] as String,
            )
        }
    }
}
