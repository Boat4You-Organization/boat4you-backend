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
        return locationRepository.findFirstByNameIgnoreCaseOrderByIdAsc(name)
    }

    fun getLocationByExternalIdAndExternalSystemId(
        externalId: Long,
        externalSystemId: Long,
    ): Location? {
        return locationRepository.findByExternalIdAndExternalSystemId(externalId, externalSystemId)
    }

    /** Partner base ids of our inland (river / canal / lake) locations, see [LocationRepository.findInlandExternalIds]. */
    fun getInlandLocationExternalIds(externalSystemId: Long): Set<Long> = locationRepository.findInlandExternalIds(externalSystemId).toSet()

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
     * Match rule (deliberately conservative, [MarinaPlaces.containmentPairs]): a
     * MARINA whose folded name is a proper substring of EXACTLY ONE other
     * MARINA's folded name in the same country, within this result set. The
     * "exactly one" guard keeps a city name that is a substring of several
     * distinct marinas ("Ibiza" ⊂ "Marina Ibiza" / "Club Nautico Ibiza" / "Port
     * of Ibiza") from collapsing unrelated marinas. Since 26.9.2026 (audit B14)
     * the data can veto a name pair: "Marina Frapa" (Rogoznica) sits inside
     * "Marina Frapa Dubrovnik" but 170 km away, and the merged row put 17
     * Rogoznica boats on the Dubrovnik catamaran landing. Plus the curated
     * location_same_place pairs no name rule finds (V9_67: "D-Marin Marina
     * Lefkas" / "Lefkas, D-Marin"), folded the same way; a curated pair that
     * touches a row already merged joins the two rows' groups ([joinGroups]).
     */
    internal fun mergeDualSourceMarinas(items: List<LocationViewDto>): List<LocationViewDto> {
        val marinas =
            items.filter { it.locationType == LocationType.MARINA && it.realId != null && it.id == "l-${it.realId}" }
        if (marinas.size < 2) return items
        val byId = marinas.associateBy { it.realId!! }
        val rows =
            marinas.map { MarinaPlaces.Marina(it.realId!!, it.name.orEmpty(), it.countryCode, it.city, it.lat, it.lon) }
        val nameLength = { id: Long -> MarinaPlaces.containmentFold(byId.getValue(id).name).length }

        // shorter -> longer (the row that stays)
        val pairs = MarinaPlaces.containmentPairs(rows).map { it.first.id to it.second.id }.toMutableList()
        val paired = pairs.flatMap { listOf(it.first, it.second) }.toMutableSet()
        val joins = mutableListOf<Pair<Long, Long>>()
        curatedSamePlacePairs().forEach { (a, b) ->
            if (a !in byId || b !in byId) return@forEach
            if (a in paired || b in paired) {
                joins.add(a to b)
                return@forEach
            }
            pairs.add(if (nameLength(a) <= nameLength(b)) a to b else b to a)
            paired.add(a)
            paired.add(b)
        }
        if (pairs.isEmpty()) return items
        val merged = if (joins.isEmpty()) pairs else joinGroups(pairs, joins, nameLength)

        val removed = merged.map { "l-${it.first}" }.toSet()
        val absorbed = merged.groupBy({ "l-${it.second}" }, { "l-${it.first}" })
        return items.mapNotNull { item ->
            val iid = item.id
            when {
                iid == null -> item
                iid in removed -> null
                iid in absorbed -> item.copy(id = (listOf(iid) + absorbed.getValue(iid)).joinToString(","))
                else -> item
            }
        }
    }

    /**
     * One marina imported three to five times (V9_79: Lavrion Main Port, Marina Baška Voda, Porto di Mergellina): each
     * curated pair alone leaves several rows of one name in the list, because a row joins at most one pair above.
     * [joins] are the curated pairs that touch a row already merged; each one merges the two groups. Of the two rows
     * that stay, the longer name wins, then the group that was formed first (the older pair, so a group keeps its
     * row when it grows), then the lower id. [pairs] (shorter -> the row that stays) come back unchanged when no join
     * connects them.
     */
    private fun joinGroups(
        pairs: List<Pair<Long, Long>>,
        joins: List<Pair<Long, Long>>,
        nameLength: (Long) -> Int,
    ): List<Pair<Long, Long>> {
        val into = LinkedHashMap<Long, Long>() // row -> the row it is merged into (itself for a row that stays)
        pairs.forEach { (shorter, stays) ->
            into[shorter] = stays
            into.putIfAbsent(stays, stays)
        }
        val formed = { id: Long -> into.keys.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
        joins.forEach { (a, b) ->
            val ra = into[a] ?: a
            val rb = into[b] ?: b
            if (ra == rb) return@forEach
            val keepA = compareValuesBy(rb, ra, { -nameLength(it) }, formed, { it }) > 0
            val keep = if (keepA) ra else rb
            val drop = if (keepA) rb else ra
            into.replaceAll { _, target -> if (target == drop) keep else target }
            into[drop] = keep
            into.putIfAbsent(keep, keep)
        }
        return into.filter { (row, target) -> row != target }.map { (row, target) -> row to target }
    }

    private fun curatedSamePlacePairs(): List<Pair<Long, Long>> =
        locationRepository.findCuratedSamePlacePairs().map { (it[0] as Number).toLong() to (it[1] as Number).toLong() }

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
