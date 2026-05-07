package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.YachtDistributionDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

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
) {
    @Transactional(readOnly = true)
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
        val marinaIds = resolveMarinas(locationIds)
        val ctx = FilterContext(
            marinaIds = marinaIds,
            startDate = startDate,
            endDate = endDate,
            vesselTypeOrdinals = vesselTypes?.map { it.ordinal },
            charterTypeOrdinals = charterTypes?.map { it.ordinal },
            mainsailTypeOrdinals = mainsailTypes?.map { it.ordinal },
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
        return YachtDistributionDto(
            priceHistogram = histogramLogWeekly(ctx),
            priceMedian = medianWeekly,
            priceMin = BigDecimal(PRICE_MIN_WEEKLY),
            priceMax = BigDecimal(PRICE_MAX_WEEKLY),
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
            byVesselType = enumCounts("vessel_type", VesselType::valueOf, ctx.copy(vesselTypeOrdinals = null)),
            byCharterType = enumCounts("charter_type", CharterType::valueOf, ctx.copy(charterTypeOrdinals = null)),
            byMainsailType = enumCounts("mainsail_type", SailTypeEnum::valueOf, ctx.copy(mainsailTypeOrdinals = null)),
            byCabins = cabinsCounts(ctx.copy(minCabins = null, maxCabins = null)),
            byManufacturer = manufacturerCounts(ctx.copy(manufacturerIds = null)),
            byModel = modelCounts(ctx.copy(modelIds = null)),
            byAmenity = amenityCounts(ctx),
        )
    }

    /** Bundles the active filter parameters that every aggregator query
     *  needs to honour so distribution counts match the listing's
     *  "Boats available" total. Vessel + charter types are passed as
     *  ordinals because the view stores enum-backed columns as INTEGER
     *  (default JPA mapping for `@Enumerated` is ORDINAL when no annotation
     *  is set on the entity). */
    private data class FilterContext(
        val marinaIds: List<Long>?,
        val startDate: LocalDate?,
        val endDate: LocalDate?,
        val vesselTypeOrdinals: List<Int>? = null,
        val charterTypeOrdinals: List<Int>? = null,
        val mainsailTypeOrdinals: List<Int>? = null,
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

    /** Resolve `did=c-54 / r-12 / l-9001` strings into the matching marina
     *  IDs (mirrors `YachtQueryingService.getMarinas`). Returns null when no
     *  location filter is active so callers can omit the WHERE clause and
     *  scan the whole view. */
    private fun resolveMarinas(locationIds: List<String>?): List<Long>? {
        if (locationIds.isNullOrEmpty()) return null
        val ids =
            locationIds
                .flatMap { resolveOne(it) }
                .distinct()
        // Empty list (only invalid prefixes / unknown ids) — return empty
        // so the WHERE clause restricts to no rows; matches main search.
        return ids
    }

    private fun resolveOne(locationId: String): List<Long> {
        val type =
            when (locationId.firstOrNull()) {
                'r' -> LocationType.REGION
                'c' -> LocationType.COUNTRY
                'l' -> LocationType.MARINA
                else -> return emptyList()
            }
        val numeric = locationId.substring(2).toIntOrNull() ?: return emptyList()
        return when (type) {
            LocationType.MARINA -> locationRepository.findById(numeric.toLong())
                .map { listOf(it.id ?: -1L).filter { i -> i >= 0 } }
                .orElse(emptyList())
            LocationType.COUNTRY -> locationRepository.findMarinasByCountryId(numeric).mapNotNull { it.id }
            LocationType.REGION -> locationRepository.findMarinasByRegionId(numeric).mapNotNull { it.id }
        }
    }

    /** Builds the additional WHERE clause that mirrors `YachtQueryingService`'s
     *  search predicates, so distribution counts always match the "Boats
     *  available" total in the listing. Three layers, all ANDed onto the
     *  caller's existing WHERE:
     *
     *  1. **Availability** (always): `offer_status != 4` — hide UNAVAILABLE
     *     offers (owner weeks, regattas, externally-booked rows). The main
     *     search applies this whenever `includeUnavailable` is false, which
     *     is true for every customer-facing call.
     *  2. **Destination** (when `did` filter active): `(location_from IN
     *     (:marinaIds) OR location_to IN (:marinaIds))`. Empty resolved list
     *     short-circuits to FALSE so counts collapse to 0, matching main
     *     search behaviour.
     *  3. **Date range** (when `startDate`/`endDate` provided): `date_from
     *     BETWEEN startDate-FLEX AND startDate+FLEX` — same flex-by-start-day
     *     contract as `buildYachtSearchPredicates`. */
    private fun whereClause(ctx: FilterContext): String {
        val parts = mutableListOf(" AND offer_status <> 4")
        when {
            ctx.marinaIds == null -> {}
            ctx.marinaIds.isEmpty() -> parts.add(" AND FALSE")
            else -> parts.add(" AND (location_from IN (:marinaIds) OR location_to IN (:marinaIds))")
        }
        when {
            ctx.startDate != null -> parts.add(" AND date_from BETWEEN :startMinusFlex AND :startPlusFlex")
            ctx.endDate != null -> parts.add(" AND date_to BETWEEN :endMinusFlex AND :endPlusFlex")
        }
        if (!ctx.vesselTypeOrdinals.isNullOrEmpty()) {
            parts.add(" AND vessel_type IN (:vesselTypeOrdinals)")
        }
        if (!ctx.charterTypeOrdinals.isNullOrEmpty()) {
            parts.add(" AND charter_type IN (:charterTypeOrdinals)")
        }
        if (!ctx.mainsailTypeOrdinals.isNullOrEmpty()) {
            parts.add(" AND mainsail_type IN (:mainsailTypeOrdinals)")
        }
        if (ctx.minBuildYear != null) parts.add(" AND build_year >= :minBuildYear")
        if (ctx.maxBuildYear != null) parts.add(" AND build_year <= :maxBuildYear")
        if (ctx.minPersons != null) parts.add(" AND max_persons >= :minPersons")
        if (ctx.maxPersons != null) parts.add(" AND max_persons <= :maxPersons")
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
        if (ctx.startDate != null) {
            q.setParameter("startMinusFlex", ctx.startDate.minusDays(DATE_FLEX_DAYS))
            q.setParameter("startPlusFlex", ctx.startDate.plusDays(DATE_FLEX_DAYS))
        } else if (ctx.endDate != null) {
            q.setParameter("endMinusFlex", ctx.endDate.minusDays(DATE_FLEX_DAYS))
            q.setParameter("endPlusFlex", ctx.endDate.plusDays(DATE_FLEX_DAYS))
        }
        if (!ctx.vesselTypeOrdinals.isNullOrEmpty()) {
            q.setParameter("vesselTypeOrdinals", ctx.vesselTypeOrdinals)
        }
        if (!ctx.charterTypeOrdinals.isNullOrEmpty()) {
            q.setParameter("charterTypeOrdinals", ctx.charterTypeOrdinals)
        }
        if (!ctx.mainsailTypeOrdinals.isNullOrEmpty()) {
            q.setParameter("mainsailTypeOrdinals", ctx.mainsailTypeOrdinals)
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

        // Match `YachtQueryingService.DATE_FLEX_DAYS` — distribution and
        // listing must use the same flex window or counts diverge.
        private const val DATE_FLEX_DAYS = 3L
    }
}
