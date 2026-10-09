package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.cache.FacetDistributionExpiry
import hr.workspace.boat4you.domains.catalouge.dto.YachtDistributionDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException
import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException.Reason
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.utils.GuletRules
import jakarta.persistence.EntityManager
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Computes the bucket aggregates that drive the V2 filter sidebar
 * histograms and per-option counts. Reads off `yacht_search_view`
 * directly (the same denormalised view the search endpoint uses) so
 * the distribution always reflects the live offer / yacht state
 * without an extra sync step.
 *
 * The first cut runs unfiltered — every yacht in the view feeds every
 * histogram. That gives the sidebar a stable distribution shape from
 * page load and avoids the chicken-and-egg of "filter to compute the
 * filter's own bar". Phase 3 will plug `YachtSearchParamObject` into
 * the WHERE clause so each filter rescales against the in-context
 * candidate set.
 *
 * Histograms use Postgres `width_bucket(value, low, high, n)` —
 * O(rows) per query, indexable on numeric columns. Buckets fixed at
 * 50 / 25 / 25 to match the design handoff bar densities.
 */
@Service
class YachtDistributionService(
    private val entityManager: EntityManager,
    private val locationRepository: LocationRepository,
    private val countryRepository: CountryRepository,
    private val regionRepository: RegionRepository,
    private val heavyQueries: HeavyQueryRunner,
    private val cacheManager: CacheManager,
) {
    private val facetCache: Cache by lazy {
        cacheManager.getCache(FACET_CACHE) ?: error("$FACET_CACHE is not configured (CacheConfig)")
    }

    /** The misses running right now, one per key; their followers wait on the future. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<YachtDistributionDto>>()

    // Cache the whole facet payload (all 9-11 COUNT(DISTINCT)/histogram/percentile
    // scans collapse into one entry) keyed on the full 24-param filter set, so
    // identical search-sidebar requests within the TTL skip the matview entirely.
    // The key is the inline list of every dimension (`List.toString()`: total and
    // collision-free across all dimensions, no hand-rolled hash).
    //
    // 1.10.2026 (Codex audit F2 + its review): cached by hand rather than with
    // `@Cacheable(sync = true)`, whose Ehcache `compute` ran the gate wait and the
    // query under the lock of the key's hash bin — a hit sharing the bin with a
    // running miss waited for it, and shed misses of one key were refused one
    // after another (N x 1.5 s). Now:
    // - a hit is a plain lock-free read and never waits for anything;
    // - concurrent misses of ONE key run the scans once (single flight): the first
    //   one goes through HeavyQueryGuard (gate + read-only transaction), the others
    //   wait for its result — or its 503 — without taking a slot or a connection;
    // - misses of different keys never wait for each other.
    // The key starts with `landing:` (no dates, no numeric slider) or `search:`, so
    // CacheConfig.FacetDistributionExpiry keeps landing counts 30 min and
    // interactive sidebar counts 3 min. No @Transactional here: a hit, a waiting
    // follower and a shed request never take a pool connection.
    fun getDistribution(
        locationIds: List<String>? = null,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        vesselTypes: List<VesselType>? = null,
        charterTypes: List<CharterType>? = null,
        mainsailTypes: List<SailTypeEnum>? = null,
        minBuildYear: Short? = null,
        maxBuildYear: Short? = null,
        minPersons: Short? = null,
        maxPersons: Short? = null,
        minCabins: Short? = null,
        maxCabins: Short? = null,
        minBerths: Short? = null,
        maxBerths: Short? = null,
        minLength: BigDecimal? = null,
        maxLength: BigDecimal? = null,
        minWc: Short? = null,
        maxWc: Short? = null,
        minEnginePower: Short? = null,
        maxEnginePower: Short? = null,
        minPriceWeekly: BigDecimal? = null,
        maxPriceWeekly: BigDecimal? = null,
        manufacturerIds: List<Long>? = null,
        modelIds: List<Long>? = null,
    ): YachtDistributionDto {
        // Mirror the search path (YachtQueryingService.buildYachtSearchPredicates):
        // an inverted range must fail the same way on both endpoints, so the facet
        // counts and the result total can never silently diverge on bad input.
        require(startDate == null || endDate == null || startDate.isBefore(endDate)) {
            "startDate must be before endDate"
        }
        // Landing = no dates and none of the numeric sliders (price, length, cabins …), which only
        // the interactive search sidebar sets. Vessel type / manufacturer / model stay landing:
        // the catamaran, manufacturer and model landing pages set them.
        val numericFilters =
            listOf(
                minBuildYear,
                maxBuildYear,
                minPersons,
                maxPersons,
                minCabins,
                maxCabins,
                minBerths,
                maxBerths,
                minLength,
                maxLength,
                minWc,
                maxWc,
                minEnginePower,
                maxEnginePower,
                minPriceWeekly,
                maxPriceWeekly,
            )
        val prefix =
            if (startDate == null && endDate == null && numericFilters.all { it == null }) {
                FacetDistributionExpiry.LANDING_KEY_PREFIX
            } else {
                FacetDistributionExpiry.SEARCH_KEY_PREFIX
            }
        val key =
            prefix +
                listOf(
                    locationIds,
                    startDate,
                    endDate,
                    vesselTypes,
                    charterTypes,
                    mainsailTypes,
                    minBuildYear,
                    maxBuildYear,
                    minPersons,
                    maxPersons,
                    minCabins,
                    maxCabins,
                    minBerths,
                    maxBerths,
                    minLength,
                    maxLength,
                    minWc,
                    maxWc,
                    minEnginePower,
                    maxEnginePower,
                    minPriceWeekly,
                    maxPriceWeekly,
                    manufacturerIds,
                    modelIds,
                ).toString()
        return cachedOnce(key) {
            heavyQueries.read(HeavyQuery.DISTRIBUTION) {
                val didScope = resolveDidScope(locationIds)
                val ctx = FilterContext(
                    marinaIds = didScope.marinaIds,
                    didCountryCodes = didScope.countryCodes,
                    regionCountryCodes = regionCountryCodes(locationIds),
                    startDate = startDate,
                    endDate = endDate,
                    vesselTypeNames = vesselTypes?.map { it.name },
                    charterTypeNames = charterTypes?.map { it.name },
                    mainsailTypeNames = mainsailTypes?.map { it.name },
                    minBuildYear = minBuildYear, maxBuildYear = maxBuildYear,
                    minPersons = minPersons, maxPersons = maxPersons,
                    minCabins = minCabins, maxCabins = maxCabins,
                    minBerths = minBerths, maxBerths = maxBerths,
                    minLength = minLength, maxLength = maxLength,
                    minWc = minWc, maxWc = maxWc,
                    minEnginePower = minEnginePower, maxEnginePower = maxEnginePower,
                    minPriceWeekly = minPriceWeekly, maxPriceWeekly = maxPriceWeekly,
                    manufacturerIds = manufacturerIds, modelIds = modelIds,
                )
                // Lower bound is hardcoded at €500 (raw `client_price` < that is mostly
                // per-day rows still present in some agency data). Upper bound expands
                // to whatever the priciest yacht in the candidate set is — that way
                // the histogram bars span the full slider track instead of crowding
                // into the cheap end (gulets / luxury yachts can hit €100k+ /week,
                // boataround.com caps at €199k). Costs one SELECT MAX per call but the
                // priciest-yacht query is light and lets the slider mirror what the
                // user actually sees in the listing.
                // Slider range is hardcoded weekly: €500 → €200,000 (Mario, 2026-04-26).
                // The 50 histogram bins are logarithmically spaced over that range so
                // the bars spread across the full track instead of crowding into the
                // cheap end (linear bins would put all gulets/catamarans in the first
                // 5–10% — see boataround.com which uses log binning too). bin 0 covers
                // ~€500–580, bin 49 covers ~€173k–200k. `YachtController` still
                // receives URL minPrice/maxPrice in linear euros (slider handle stays
                // linear) and divides by 7 before the WHERE clause.
                val medianWeekly = priceMedianWeekly(ctx)
                YachtDistributionDto(
                    priceHistogram = histogramLogWeekly(ctx),
                    priceMedian = medianWeekly,
                    priceMin = BigDecimal(PRICE_MIN_WEEKLY),
                    priceMax = BigDecimal(PRICE_MAX_WEEKLY),
                    maxDiscountPerc = maxDiscountPerc(ctx),
                    lengthHistogram = histogramLog(
                        column = "length",
                        low = LENGTH_MIN.toDouble(),
                        high = LENGTH_MAX.toDouble(),
                        buckets = LENGTH_BUCKETS,
                        ctx = ctx,
                    ),
                    engineHistogram = histogramLog(
                        column = "engine_power",
                        low = ENGINE_MIN.toDouble(),
                        high = ENGINE_MAX.toDouble(),
                        buckets = ENGINE_BUCKETS,
                        ctx = ctx,
                    ),
                    // Facet-count pattern: each tile group is counted with its own
                    // filter dimension excluded so users still see how many boats
                    // exist in unselected tiles. Without this, picking "Catamaran"
                    // would zero out Sailing/Motorboat/etc. counts (the WHERE clause
                    // filters them out before the GROUP BY). Other filters (location,
                    // dates, availability) still apply.
                    byVesselType = enumCounts("vessel_type", VesselType::valueOf, ctx.copy(vesselTypeNames = null)),
                    byCharterType = charterTypeCounts(ctx.copy(charterTypeNames = null)),
                    byMainsailType = enumCounts("mainsail_type", SailTypeEnum::valueOf, ctx.copy(mainsailTypeNames = null)),
                    byCabins = cabinsCounts(ctx.copy(minCabins = null, maxCabins = null)),
                    byManufacturer = manufacturerCounts(ctx.copy(manufacturerIds = null)),
                    byModel = modelCounts(ctx.copy(modelIds = null)),
                    byAmenity = amenityCounts(ctx),
                )
            }
        }
    }

    /**
     * The cached distribution for [key], or the result of ONE [compute] shared by every concurrent
     * miss of that key (see the comment on [getDistribution]). A failure — a 503 shed by the gate
     * included — is handed to the waiting followers as well and is never cached.
     */
    private fun cachedOnce(
        key: String,
        compute: () -> YachtDistributionDto,
    ): YachtDistributionDto {
        facetCache.get(key, YachtDistributionDto::class.java)?.let { return it }
        val mine = CompletableFuture<YachtDistributionDto>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return awaitRunning(running)
        try {
            // A miss of this key may have finished between the lookup above and putIfAbsent.
            val result = facetCache.get(key, YachtDistributionDto::class.java) ?: compute().also { facetCache.put(key, it) }
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            // After the put: a request arriving now finds the entry, not an empty slot.
            inFlight.remove(key, mine)
        }
    }

    /** A follower: the running miss's result or its own exception (a 503 stays a 503). */
    private fun awaitRunning(running: CompletableFuture<YachtDistributionDto>): YachtDistributionDto =
        try {
            running.get(FOLLOWER_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw HeavyQueryBusyException(HeavyQuery.DISTRIBUTION, Reason.TIMED_OUT, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HeavyQueryBusyException(HeavyQuery.DISTRIBUTION, Reason.SATURATED, e)
        }

    /** Bundles the active filter parameters that every aggregator query
     *  needs to honour so distribution counts match the listing's
     *  "Boats available" total. Vessel + charter + mainsail types are passed
     *  as enum names (`enum.name`) — the view exposes enum-backed columns
     *  as VARCHAR after F2-018 migration to `@Enumerated(EnumType.STRING)`. */
    private data class FilterContext(
        val marinaIds: List<Long>?,
        /** 2-letter codes for `c-…` did entries — filtered on the view's indexed
         *  country_code/country_code_to columns instead of a 200+ marina IN-list
         *  (which forced a full-view walk on every country facet query). */
        val didCountryCodes: List<String>? = null,
        /** Own countries of the `r-…` did entries (search: deriveRegionCountryCodes). */
        val regionCountryCodes: List<String>? = null,
        val startDate: LocalDate?,
        val endDate: LocalDate?,
        val vesselTypeNames: List<String>? = null,
        val charterTypeNames: List<String>? = null,
        val mainsailTypeNames: List<String>? = null,
        val minBuildYear: Short? = null,
        val maxBuildYear: Short? = null,
        val minPersons: Short? = null,
        val maxPersons: Short? = null,
        val minCabins: Short? = null,
        val maxCabins: Short? = null,
        val minBerths: Short? = null,
        val maxBerths: Short? = null,
        val minLength: BigDecimal? = null,
        val maxLength: BigDecimal? = null,
        val minWc: Short? = null,
        val maxWc: Short? = null,
        val minEnginePower: Short? = null,
        val maxEnginePower: Short? = null,
        // Price arrives weekly; main search does the / 7 split — apply same here.
        val minPriceWeekly: BigDecimal? = null,
        val maxPriceWeekly: BigDecimal? = null,
        val manufacturerIds: List<Long>? = null,
        val modelIds: List<Long>? = null,
    )

    /** The `did` filter split by granularity: region/marina ids expand into
     *  marina-id lists, country ids resolve to 2-letter codes (filtered on the
     *  view's indexed country_code columns). Both null when no location filter
     *  is active so callers can omit the WHERE clause and scan the whole view. */
    private data class DidScope(val marinaIds: List<Long>?, val countryCodes: List<String>?)

    /** Resolve `did=c-54 / r-12 / l-9001` strings (mirrors the split in
     *  `YachtQueryingService.buildYachtSearchPredicates`). */
    private fun resolveDidScope(locationIds: List<String>?): DidScope {
        if (locationIds.isNullOrEmpty()) return DidScope(null, null)
        val countryCodes =
            locationIds
                .filter { it.firstOrNull() == 'c' }
                // `drop(2)`, not `substring(2)`: a one-character token (`did=c`) is an unknown
                // destination like any other, not a 500 (16.9.2026 cusma2 load incident review).
                .mapNotNull { it.drop(2).toLongOrNull() }
                .mapNotNull { countryRepository.findById(it).orElse(null)?.code2?.uppercase() }
                .distinct()
        // Empty lists (only invalid prefixes / unknown ids) stay non-null so the
        // WHERE clause restricts to no rows; matches the pre-split behaviour.
        val marinaIds =
            locationIds
                .filterNot { it.firstOrNull() == 'c' }
                .flatMap { resolveOne(it) }
                .distinct()
        return DidScope(marinaIds, countryCodes)
    }

    /** Mirror of YachtQueryingService.deriveRegionCountryCodes: the own countries of the region ids in `did`. */
    private fun regionCountryCodes(locationIds: List<String>?): List<String>? =
        locationIds
            ?.filter { it.firstOrNull() == 'r' }
            ?.mapNotNull { it.drop(2).toLongOrNull() }
            ?.mapNotNull { regionRepository.findById(it).orElse(null)?.countryCode?.uppercase() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }

    private fun resolveOne(locationId: String): List<Long> {
        val type =
            when (locationId.firstOrNull()) {
                'r' -> LocationType.REGION
                'l' -> LocationType.MARINA
                // 'c' never reaches here — resolveDidScope routes country ids
                // to the indexed country-code filter instead of a marina list.
                else -> return emptyList()
            }
        // `drop(2)` for the same reason as in [resolveDidScope]: `did=l` must not throw.
        val numeric = locationId.drop(2).toIntOrNull() ?: return emptyList()
        return when (type) {
            // Mirror YachtQueryingService.getMarinas: a marina can exist twice (one row
            // per provider, "Marina Kastela" vs "Marina Kaštela"). Expand to every
            // same-place sibling so the facet counts the SAME fleets the search lists —
            // findById alone counted only the picked provider's boats, so the facet
            // badge ran lower than the result total (audit: facet < available).
            LocationType.MARINA -> {
                val marina = locationRepository.findById(numeric.toLong()).orElse(null)
                when {
                    marina == null -> emptyList()
                    marina.name.isNullOrBlank() -> listOfNotNull(marina.id)
                    else ->
                        locationRepository.findSamePlaceMarinaIds(marina.id!!)
                            .ifEmpty { listOfNotNull(marina.id) }
                }
            }
            LocationType.REGION -> locationRepository.findMarinasByRegionId(numeric).mapNotNull { it.id }
            else -> emptyList()
        }
    }

    /** Builds the additional WHERE clause that mirrors `YachtQueryingService`'s
     *  search predicates, so distribution counts always match the "Boats
     *  available" total in the listing. Layers, all ANDed onto the caller's
     *  existing WHERE:
     *
     *  1. **Availability** (always): `offer_status <> 'UNAVAILABLE'` — hide
     *     UNAVAILABLE offers (owner weeks, regattas, externally-booked rows).
     *     The main search applies this whenever `includeUnavailable` is false,
     *     which is true for every customer-facing call. STAYS as the matview
     *     pre-filter exactly like `buildYachtSearchPredicates`.
     *  2. **Destination** (when `did` filter active): marina/region ids match
     *     `(location_from IN (:marinaIds) OR location_to IN (:marinaIds))`;
     *     country ids match the indexed `country_code`/`country_code_to`
     *     columns — both OR-ed together. Nothing resolved short-circuits to
     *     FALSE so counts collapse to 0, matching main search behaviour.
     *  3. **Honest date interval-overlap** (Deploy 4 — replaces the old
     *     `date_from BETWEEN startDate±FLEX` start-day clamp). A slot qualifies
     *     when its OWN window overlaps the request padded by [NEARBY_WINDOW_DAYS]:
     *     `date_from < :endPlus AND date_to > :startMinus`, judged on the slot's
     *     real `date_from`/`date_to` — never a start-day-only clamp. Custom
     *     yachts (entry_type=CUSTOM) carry NULL `date_from`/`date_to`, so an
     *     `OR date_from IS NULL` escape hatch keeps them counted (inquiry-only
     *     listings always show), mirroring `buildYachtSearchPredicates`.
     *  4. **Live hard-block** (when BOTH dates present, same as search): a
     *     correlated `NOT EXISTS` half-open overlap against `external_reservations`
     *     drops yachts whose REAL window is firmly blocked (status RESERVATION /
     *     SERVICE only — never OPTION, so an agency optioning a whole marina keeps
     *     every yacht counted, badged inquiry-only). Uses the RAW request
     *     [startDate, endDate) (not padded), exactly like
     *     `buildHardBlockOverlapSubquery`. Custom yachts have no reservation rows
     *     so the NOT EXISTS is vacuously true and never drops them. */
    private fun whereClause(ctx: FilterContext): String {
        val parts = mutableListOf(" AND offer_status <> 'UNAVAILABLE'")
        val hasMarinas = !ctx.marinaIds.isNullOrEmpty()
        val hasDidCountries = !ctx.didCountryCodes.isNullOrEmpty()
        when {
            ctx.marinaIds == null && ctx.didCountryCodes == null -> {}
            !hasMarinas && !hasDidCountries -> parts.add(" AND FALSE")
            else -> {
                // Pickup only, and undated also based here, like the search (audit B14).
                parts.add(" AND ${DestinationScopeSql.pickup(hasMarinas, hasDidCountries)}")
                if (ctx.startDate == null && ctx.endDate == null) parts.add(" AND ${DestinationScopeSql.basedHere(hasMarinas, hasDidCountries)}")
            }
        }
        if (ctx.startDate != null && ctx.endDate != null) {
            // Slot window [date_from, date_to) overlaps padded request
            // [startMinus, endPlus). NULL date_from = custom yacht ⇒ always kept.
            parts.add(" AND (date_from IS NULL OR (date_from < :endPlus AND date_to > :startMinus))")
            // Live hard-block: drop yachts firmly reserved/serviced over the RAW
            // requested period. OPTION stays visible. Vacuously true for customs.
            parts.add(
                " AND NOT EXISTS (" +
                    "SELECT 1 FROM external_reservations er " +
                    "WHERE er.yacht_id = yacht_search_view.id " +
                    "AND er.status IN ('RESERVATION', 'SERVICE') " +
                    "AND er.date_from < :hardBlockEnd AND er.date_to > :hardBlockStart)",
            )
        } else if (ctx.startDate != null) {
            // Open-ended start: slot's window must reach on/after the padded start.
            parts.add(" AND (date_from IS NULL OR date_to > :startMinus)")
        } else if (ctx.endDate != null) {
            // Open-ended end: slot's window must start on/before the padded end.
            parts.add(" AND (date_from IS NULL OR date_from < :endPlus)")
        } else {
            // Undated: bookable offers only (starting today or later), like the search (audit B12), one card per
            // physical boat (audit B17, yacht_listing_twin).
            parts.add(" AND (date_from IS NULL OR date_from >= CURRENT_DATE)")
            parts.add(" AND NOT EXISTS (SELECT 1 FROM yacht_listing_twin t WHERE t.yacht_id = yacht_search_view.id)")
        }
        if (!ctx.regionCountryCodes.isNullOrEmpty()) {
            // A region search lists only boats in the region's own country (search: deriveRegionCountryCodes), so
            // MMK's sea-wide "Ionian" does not count Italian boats in a Greek landing's chips.
            parts.add(" AND country_code IN (:regionCountryCodes)")
        }
        if (!ctx.vesselTypeNames.isNullOrEmpty()) {
            parts.add(" AND vessel_type IN (:vesselTypeNames)")
        }
        if (!ctx.charterTypeNames.isNullOrEmpty()) {
            // A gulet is never bareboat: BAREBOAT never matches it, CREWED always does (GuletRules, like the search).
            parts.add(" AND ${GuletRules.charterTypeSql(ctx.charterTypeNames.map { CharterType.valueOf(it) }, "charterTypeNames")}")
        }
        if (!ctx.mainsailTypeNames.isNullOrEmpty()) {
            parts.add(" AND mainsail_type IN (:mainsailTypeNames)")
        }
        if (ctx.minBuildYear != null) parts.add(" AND build_year >= :minBuildYear")
        if (ctx.maxBuildYear != null) parts.add(" AND build_year <= :maxBuildYear")
        // Same people semantics as the listing (YachtQueryingService): max people on board, else berths.
        if (ctx.minPersons != null) parts.add(" AND COALESCE(max_persons, berths) >= :minPersons")
        if (ctx.maxPersons != null) parts.add(" AND COALESCE(max_persons, berths) <= :maxPersons")
        if (ctx.minCabins != null) parts.add(" AND cabins >= :minCabins")
        if (ctx.maxCabins != null) parts.add(" AND cabins <= :maxCabins")
        if (ctx.minBerths != null) parts.add(" AND berths >= :minBerths")
        if (ctx.maxBerths != null) parts.add(" AND berths <= :maxBerths")
        if (ctx.minLength != null) parts.add(" AND length >= :minLength")
        if (ctx.maxLength != null) parts.add(" AND length <= :maxLength")
        if (ctx.minWc != null) parts.add(" AND wc >= :minWc")
        if (ctx.maxWc != null) parts.add(" AND wc <= :maxWc")
        if (ctx.minEnginePower != null) parts.add(" AND engine_power >= :minEnginePower")
        if (ctx.maxEnginePower != null) parts.add(" AND engine_power <= :maxEnginePower")
        // Price filter: slider sends weekly euros; column is per-day → divide by 7
        if (ctx.minPriceWeekly != null) parts.add(" AND client_price >= :minPriceDaily")
        if (ctx.maxPriceWeekly != null) parts.add(" AND client_price <= :maxPriceDaily")
        if (!ctx.manufacturerIds.isNullOrEmpty()) parts.add(" AND manufacturer_id IN (:manufacturerIds)")
        if (!ctx.modelIds.isNullOrEmpty()) parts.add(" AND model_id IN (:modelIds)")
        return parts.joinToString("")
    }

    /** Logarithmically-binned histogram on weekly prices. 50 bins covering
     *  €500–€200,000 with bin width growing geometrically — yacht prices
     *  cluster at the low end, so log binning spreads the bars across the
     *  slider where a linear histogram would compress them into the first
     *  5–10% of the track. */
    private fun histogramLogWeekly(ctx: FilterContext): List<Long> {
        val sql =
            """
            SELECT bucket, COUNT(*) AS cnt
            FROM (
              SELECT GREATEST(0, LEAST(${PRICE_BUCKETS - 1},
                FLOOR(${PRICE_BUCKETS} * LN(client_price * 7.0 / ${PRICE_MIN_WEEKLY})
                      / LN(${PRICE_MAX_WEEKLY}.0 / ${PRICE_MIN_WEEKLY}))::int
              )) AS bucket
              FROM yacht_search_view
              WHERE client_price IS NOT NULL
                AND client_price * 7 >= ${PRICE_MIN_WEEKLY}${whereClause(ctx)}
            ) t
            GROUP BY bucket
            ORDER BY bucket
            """.trimIndent()
        val q = entityManager.createNativeQuery(sql)
        bindFilters(q, ctx)
        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any>>
        val out = LongArray(PRICE_BUCKETS)
        rows.forEach {
            val idx = (it[0] as Number).toInt()
            if (idx in 0 until PRICE_BUCKETS) out[idx] = (it[1] as Number).toLong()
        }
        return out.toList()
    }

    /** Generic log-binned histogram on a numeric column. Same shape as
     *  `histogramLogWeekly` but parameterised so it can drive the length
     *  slider (4–56m) too. Bins skewed-right so common boat sizes (8–15m)
     *  spread across the middle of the track instead of crowding the low end. */
    private fun histogramLog(column: String, low: Double, high: Double, buckets: Int, ctx: FilterContext): List<Long> {
        @Suppress("SqlSourceToSinkFlow")
        val sql =
            """
            SELECT bucket, COUNT(*) AS cnt
            FROM (
              SELECT GREATEST(0, LEAST(${buckets - 1},
                FLOOR($buckets * LN($column / :low) / LN(:high / :low))::int
              )) AS bucket
              FROM yacht_search_view
              WHERE $column IS NOT NULL AND $column >= :low${whereClause(ctx)}
            ) t
            GROUP BY bucket
            ORDER BY bucket
            """.trimIndent()
        val q = entityManager.createNativeQuery(sql)
        q.setParameter("low", low)
        q.setParameter("high", high)
        bindFilters(q, ctx)
        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any>>
        val out = LongArray(buckets)
        rows.forEach {
            val idx = (it[0] as Number).toInt()
            if (idx in 0 until buckets) out[idx] = (it[1] as Number).toLong()
        }
        return out.toList()
    }

    private fun bindFilters(q: jakarta.persistence.Query, ctx: FilterContext) {
        if (!ctx.marinaIds.isNullOrEmpty()) q.setParameter("marinaIds", ctx.marinaIds)
        if (!ctx.didCountryCodes.isNullOrEmpty()) q.setParameter("didCountryCodes", ctx.didCountryCodes)
        if (!ctx.regionCountryCodes.isNullOrEmpty()) q.setParameter("regionCountryCodes", ctx.regionCountryCodes)
        // Honest interval-overlap bind, matching buildYachtSearchPredicates:
        // padded window for the slot overlap, RAW window for the live hard-block.
        if (ctx.startDate != null && ctx.endDate != null) {
            q.setParameter("startMinus", ctx.startDate.minusDays(NEARBY_WINDOW_DAYS))
            q.setParameter("endPlus", ctx.endDate.plusDays(NEARBY_WINDOW_DAYS))
            q.setParameter("hardBlockStart", ctx.startDate)
            q.setParameter("hardBlockEnd", ctx.endDate)
        } else if (ctx.startDate != null) {
            q.setParameter("startMinus", ctx.startDate.minusDays(NEARBY_WINDOW_DAYS))
        } else if (ctx.endDate != null) {
            q.setParameter("endPlus", ctx.endDate.plusDays(NEARBY_WINDOW_DAYS))
        }
        if (!ctx.vesselTypeNames.isNullOrEmpty()) {
            q.setParameter("vesselTypeNames", ctx.vesselTypeNames)
        }
        if (!ctx.charterTypeNames.isNullOrEmpty()) {
            q.setParameter("charterTypeNames", ctx.charterTypeNames)
        }
        if (!ctx.mainsailTypeNames.isNullOrEmpty()) {
            q.setParameter("mainsailTypeNames", ctx.mainsailTypeNames)
        }
        ctx.minBuildYear?.let { q.setParameter("minBuildYear", it) }
        ctx.maxBuildYear?.let { q.setParameter("maxBuildYear", it) }
        ctx.minPersons?.let { q.setParameter("minPersons", it) }
        ctx.maxPersons?.let { q.setParameter("maxPersons", it) }
        ctx.minCabins?.let { q.setParameter("minCabins", it) }
        ctx.maxCabins?.let { q.setParameter("maxCabins", it) }
        ctx.minBerths?.let { q.setParameter("minBerths", it) }
        ctx.maxBerths?.let { q.setParameter("maxBerths", it) }
        ctx.minLength?.let { q.setParameter("minLength", it) }
        ctx.maxLength?.let { q.setParameter("maxLength", it) }
        ctx.minWc?.let { q.setParameter("minWc", it) }
        ctx.maxWc?.let { q.setParameter("maxWc", it) }
        ctx.minEnginePower?.let { q.setParameter("minEnginePower", it) }
        ctx.maxEnginePower?.let { q.setParameter("maxEnginePower", it) }
        ctx.minPriceWeekly?.let { q.setParameter("minPriceDaily", it.divide(BigDecimal(7), 2, RoundingMode.HALF_UP)) }
        ctx.maxPriceWeekly?.let { q.setParameter("maxPriceDaily", it.divide(BigDecimal(7), 2, RoundingMode.HALF_UP)) }
        if (!ctx.manufacturerIds.isNullOrEmpty()) {
            q.setParameter("manufacturerIds", ctx.manufacturerIds)
        }
        if (!ctx.modelIds.isNullOrEmpty()) {
            q.setParameter("modelIds", ctx.modelIds)
        }
    }

    /** Bucket-wise count of rows whose [column] falls inside each
     *  Postgres `width_bucket` slot from [low] (inclusive) to [high]
     *  (exclusive). Bar 0 = below [low], bar n = at-or-above [high];
     *  we drop those edge buckets so the array indexes line up 1:1
     *  with the visible bars in the UI. */
    private fun histogram(
        column: String,
        low: Number,
        high: Number,
        buckets: Int,
        ctx: FilterContext,
    ): List<Long> {
        @Suppress("SqlSourceToSinkFlow") // column name is a private const, not user input
        val sql =
            """
            SELECT bucket, COUNT(*) AS cnt
            FROM (
              SELECT width_bucket($column, :low, :high, $buckets) AS bucket
              FROM yacht_search_view
              WHERE $column IS NOT NULL${whereClause(ctx)}
            ) t
            WHERE bucket BETWEEN 1 AND $buckets
            GROUP BY bucket
            ORDER BY bucket
            """.trimIndent()
        val q = entityManager.createNativeQuery(sql)
        q.setParameter("low", low)
        q.setParameter("high", high)
        bindFilters(q, ctx)
        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any>>
        val out = LongArray(buckets)
        rows.forEach {
            val idx = (it[0] as Number).toInt() - 1
            if (idx in 0 until buckets) out[idx] = (it[1] as Number).toLong()
        }
        return out.toList()
    }

    /** Median of `client_price * 7` (weekly) within the filtered set. */
    private fun priceMedianWeekly(ctx: FilterContext): BigDecimal? {
        val sql =
            "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY client_price * 7) " +
                "FROM yacht_search_view WHERE client_price IS NOT NULL${whereClause(ctx)}"
        val q = entityManager.createNativeQuery(sql)
        bindFilters(q, ctx)
        val raw = q.singleResult as Number?
        return raw?.let { BigDecimal(it.toString()).setScale(0, RoundingMode.HALF_UP) }
    }

    /** Highest partner-discount percentage in the filtered set: rounded
     *  MAX of (1 - client_price/list_price) — per-day values, so the ratio
     *  is duration-independent. Guards: NULLIF for zero list prices and a
     *  client < list predicate so bad partner data (client above list) can
     *  never produce a negative "discount". Capped at 90 — anything higher
     *  is partner data noise, not a real campaign discount. */
    private fun maxDiscountPerc(ctx: FilterContext): Int? {
        val sql =
            "SELECT MAX(ROUND((1 - client_price / NULLIF(list_price, 0)) * 100)) " +
                "FROM yacht_search_view WHERE client_price IS NOT NULL AND list_price IS NOT NULL " +
                "AND client_price < list_price${whereClause(ctx)}"
        val q = entityManager.createNativeQuery(sql)
        bindFilters(q, ctx)
        val raw = q.singleResult as Number?
        return raw?.toInt()?.coerceAtMost(90)
    }

    /** GROUP BY on an enum-backed column. The view stores enums as
     *  Postgres `INTEGER` (default JPA mapping for `@Enumerated` is
     *  ORDINAL when no annotation is set on the entity), so the
     *  native query returns Integer rows. We cast back to the right
     *  enum constant via `enumValues<E>()[ordinal]`. Rows with NULL
     *  or out-of-range ordinals are dropped so the response never
     *  contains invalid keys.
     *
     *  `COUNT(DISTINCT id)` (not `COUNT(*)`) so per-type counts stay
     *  consistent with the listing's "Boats available" total — the
     *  view has one row per offer, and a single yacht can have several
     *  candidate offers in the flex window. Counting rows would
     *  over-report it. */
    private inline fun <reified E : Enum<E>> enumCounts(
        column: String,
        @Suppress("UNUSED_PARAMETER") parse: (String) -> E,
        ctx: FilterContext,
    ): Map<E, Long> {
        @Suppress("SqlSourceToSinkFlow")
        val sql =
            "SELECT $column, COUNT(DISTINCT id) FROM yacht_search_view " +
                "WHERE $column IS NOT NULL${whereClause(ctx)} GROUP BY $column"
        val q = entityManager.createNativeQuery(sql)
        bindFilters(q, ctx)
        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any>>
        val constants = enumValues<E>()
        val out = mutableMapOf<E, Long>()
        rows.forEach { row ->
            val raw = row[0] ?: return@forEach
            val parsed: E? =
                when (raw) {
                    is Number -> constants.getOrNull(raw.toInt())
                    is String -> runCatching { java.lang.Enum.valueOf(E::class.java, raw) }.getOrNull()
                    else -> null
                }
            parsed?.let { out[it] = (row[1] as Number).toLong() }
        }
        return out
    }

    /**
     * Per-charter-type distinct yacht count, counted the way the charterType filter matches (GuletRules): a gulet's
     * BAREBOAT row counts nowhere and every gulet counts under CREWED, so each chip's count equals the boats its filter
     * lists. The second VALUES row exists for gulets only (NULL otherwise, dropped before the aggregate), so the scan
     * and the aggregate stay the size of the old GROUP BY charter_type.
     */
    private fun charterTypeCounts(ctx: FilterContext): Map<CharterType, Long> {
        val gulet = GuletRules.SQL_IS_GULET
        val sql =
            "SELECT ct.charter, COUNT(DISTINCT id) FROM yacht_search_view " +
                "CROSS JOIN LATERAL (VALUES (CASE WHEN charter_type = 'BAREBOAT' AND $gulet THEN NULL ELSE charter_type END), " +
                "(CASE WHEN $gulet THEN 'CREWED' END)) AS ct(charter) " +
                "WHERE ct.charter IS NOT NULL${whereClause(ctx)} GROUP BY ct.charter"
        val q = entityManager.createNativeQuery(sql)
        bindFilters(q, ctx)
        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any>>
        return rows
            .mapNotNull { row ->
                val type = CharterType.entries.firstOrNull { it.name == row[0]?.toString() } ?: return@mapNotNull null
                type to (row[1] as Number).toLong()
            }.toMap()
    }

    /** Per-manufacturer distinct yacht count under the active filter set
     *  (excluding the manufacturer dimension itself — facet pattern). The
     *  frontend uses this to grey out manufacturer dropdown entries that
     *  have 0 yachts in the current context (e.g. Aicon while filtering
     *  catamarans). Returns only manufacturer ids with > 0 yachts; absent
     *  ids are treated as disabled on the frontend. */
    private fun manufacturerCounts(ctx: FilterContext): Map<Long, Long> {
        val sql =
            "SELECT manufacturer_id, COUNT(DISTINCT id) FROM yacht_search_view " +
                "WHERE manufacturer_id IS NOT NULL${whereClause(ctx)} GROUP BY manufacturer_id"
        val q = entityManager.createNativeQuery(sql)
        bindFilters(q, ctx)
        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any>>
        return rows.associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
    }

    /** Per-amenity (equipment.id) distinct yacht count under the active
     *  filter set. Frontend greys out amenity dropdown entries with 0 yachts
     *  in the current context. Amenities live on `yacht_equipment`, so we
     *  JOIN against `yacht_search_view` to apply the rest of the filters. */
    private fun amenityCounts(ctx: FilterContext): Map<Long, Long> {
        // Subquery filters yachts by all active dimensions, then JOIN
        // `yacht_equipment` to GROUP BY equipment_id. Avoids aliasing every
        // whereClause column for a single helper.
        val sql =
            "SELECT ye.equipment_id, COUNT(DISTINCT ye.yacht_id) " +
                "FROM yacht_equipment ye " +
                "WHERE ye.yacht_id IN (" +
                "  SELECT id FROM yacht_search_view WHERE 1=1${whereClause(ctx)}" +
                ") AND ye.equipment_id IS NOT NULL " +
                "GROUP BY ye.equipment_id"
        val q = entityManager.createNativeQuery(sql)
        bindFilters(q, ctx)
        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any>>
        return rows.associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
    }

    /** Per-model distinct yacht count under the active filter set (excluding
     *  the model dimension itself — facet pattern). Frontend greys out model
     *  dropdown entries with 0 yachts in the current context. */
    private fun modelCounts(ctx: FilterContext): Map<Long, Long> {
        val sql =
            "SELECT model_id, COUNT(DISTINCT id) FROM yacht_search_view " +
                "WHERE model_id IS NOT NULL${whereClause(ctx)} GROUP BY model_id"
        val q = entityManager.createNativeQuery(sql)
        bindFilters(q, ctx)
        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any>>
        return rows.associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
    }

    /** Cabin counts capped at 6 — anything ≥ 6 collapses into the "6+"
     *  bucket which the UI pill uses. `COUNT(DISTINCT id)` for the same
     *  reason as `enumCounts`: matches per-yacht totals in the listing. */
    private fun cabinsCounts(ctx: FilterContext): Map<Int, Long> {
        val sql =
            """
            SELECT LEAST(cabins, 6) AS bucket, COUNT(DISTINCT id)
            FROM yacht_search_view
            WHERE cabins IS NOT NULL${whereClause(ctx)}
            GROUP BY bucket
            ORDER BY bucket
            """.trimIndent()
        val q = entityManager.createNativeQuery(sql)
        bindFilters(q, ctx)
        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any>>
        return rows.associate { (it[0] as Number).toInt() to (it[1] as Number).toLong() }
    }

    companion object {
        private const val FACET_CACHE = "facetDistributionCache"

        /**
         * Longest a follower waits for the running miss of its key: the leader's gate wait (1.5 s),
         * a pool connection (Hikari 20 s) and its 30 s transaction, with a margin. Then 503.
         */
        private const val FOLLOWER_WAIT_SECONDS = 60L

        // Histogram bucket counts and ranges. Match the V2 sidebar
        // sliders' default min/max so each bar maps cleanly to a slot
        // on the visible track.
        // Hardcoded weekly price range — slider stays static at €500 → €200,000
        // (Mario, 2026-04-26: "fixno, samo se treba prikazivati plovila ponuđena
        // gdje su u tom rasponu"). Listing card shows weekly totals; the slider
        // matches. Static avoids an extra MIN/MAX round-trip per filter change.
        private const val PRICE_MIN_WEEKLY = 500
        private const val PRICE_MAX_WEEKLY = 200_000
        private const val PRICE_BUCKETS = 50

        private const val LENGTH_MIN = 4
        private const val LENGTH_MAX = 56
        private const val LENGTH_BUCKETS = 25

        // Log binning needs low > 0 (LN(0) is undefined). 5 hp = realistic
        // smallest yacht engine; tiny outboards / 0-hp tow boats fall outside.
        private const val ENGINE_MIN = 5
        private const val ENGINE_MAX = 7_500
        private const val ENGINE_BUCKETS = 25

        // Match `YachtQueryingService.NEARBY_WINDOW_DAYS` — distribution and
        // listing must pad the honest interval-overlap window identically or
        // facet counts diverge from the "Boats available" total (Deploy 4).
        private const val NEARBY_WINDOW_DAYS = 3L
    }
}
