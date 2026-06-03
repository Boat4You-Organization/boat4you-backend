package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtDetailsResponse
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtResponse
import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import hr.workspace.boat4you.domains.catalouge.dto.VesselTypeYachtCountDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtAvailabilityDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtDetailsDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchResponseDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.ReplacementSearchRow
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyNotActiveException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtNotActiveException
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtSearchSelectResult
import hr.workspace.boat4you.domains.catalouge.jpa.YachtSearchView
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtMapper
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.cache.annotation.Cacheable
import org.springframework.core.io.Resource
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class YachtQueryingService(
    private val entityManager: EntityManager,
    private val yachtRepository: YachtRepository,
    private val locationRepository: LocationRepository,
    private val externalReservationRepository: ExternalReservationRepository,
    private val yachtMapper: YachtMapper,
    private val offerRepository: OfferRepository,
    private val customYachtViewRepository: CustomYachtViewRepository,
    private val customYachtDetailRepository: CustomYachtDetailRepository,
    private val yachtTranslationRepository: YachtTranslationRepository,
    private val offerMapper: OfferMapper,
    private val fileSystemService: FileSystemService,
    private val exchangeRateCalculationService: ExchangeRateCalculationService,
    private val yachtExtraRepository: YachtExtraRepository,
    private val externalBaseRepository: ExternalBaseRepository,
    private val regionRepository: RegionRepository,
) {
    companion object {
        private const val MAX_PAGE_SIZE = 100

        /**
         * Tolerance on each side of the user's requested period when filtering
         * yacht offers. A search for 04.07.–11.07. (Sat–Sat) also returns
         * Mon–Mon or Thu–Thu offers that sit within 3 days of those bounds
         * — mirrors what larger brokers (MMK, Boataround) show as "closest
         * day" suggestions instead of silently dropping them.
         */
        private const val DATE_FLEX_DAYS = 3L

        /**
         * Customer-facing amenity priority used for the search-result card's
         * top-3 icon row. Items earlier in this list rank higher. This order
         * is deliberately different from [Equipment.filterOrder] — that column
         * is tuned for the filter panel (grouped by category), whereas here
         * we lead with the items that drive booking decisions
         * (AC, dinghy, bimini, water toys…).
         */
        private val CARD_AMENITY_PRIORITY: List<String> =
            listOf(
                "air-conditioning",
                "wifi",
                "dinghy",
                "generator",
                "outside-GPS-plotter",
                "solar-panels",
                "water-toys",
                "snorkel-sets",
                "outside-shower",
                "fridge",
                "bimini",
                "autopilot",
                "bow-thruster",
                "radar",
                "heating",
            )
    }

    fun getYachts(
        searchParams: YachtSearchParamObject,
        sortBy: String?,
        language: LanguageEnum,
        page: Int,
        size: Int,
    ): PageImpl<YachtSearchResponseDto> {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(YachtSearchSelectResult::class.java)
        val root = cq.from(YachtSearchView::class.java)

        // V1_90 migrated offer.status from smallint(ORDINAL) to varchar(STRING),
        // so the view column emits enum names. Path the column as OfferStatus
        // so Hibernate compares enum-vs-enum and Postgres doesn't blow up with
        // `operator does not exist: character varying = integer` like the
        // pre-fix `root.get<Int>("offerStatus")` produced.
        val offerStatusRaw = root.get<OfferStatus>("offerStatus")

        // Pre-reserved states (OPTION, OPTION_WAITING, RESERVED, SERVICE) project
        // to their OfferStatus.value Int codes (2/3/5/7); everything else (FREE,
        // OPTION_EXPIRED, CANCELLED, INFO, UNKNOWN — all rendered "Available" on
        // the card) collapses to FREE=1. UNAVAILABLE=4 is already filtered out
        // earlier in this query. If ANY matching offer is under option, the
        // yacht shows the "Pre-reserved" badge / SPECIAL PROMOTION ribbon
        // instead of being masked by a FREE offer for another week.
        //
        // Per-status MAX(CASE) wrapped in GREATEST instead of one multi-branch
        // CASE: Hibernate 6.6 SQM folded chained `WHEN status=X THEN X.value`
        // branches into `WHEN status IN (...) THEN status ELSE 1`, mixing the
        // varchar column (THEN) with an int literal (ELSE) and tripping
        // Postgres' `operator does not exist: character varying = integer`.
        // Single-WHEN CASEs aren't subject to that merge optimization; GREATEST
        // picks the highest priority value, preserving the original semantics
        // (SERVICE=7 > RESERVED=5 > OPTION_WAITING=3 > OPTION=2 > FREE=1).
        val maxIfStatusEquals: (OfferStatus) -> Expression<Int> = { status ->
            cb.max(
                cb.selectCase<Int>()
                    .`when`(cb.equal(offerStatusRaw, status), cb.literal(status.value))
                    .otherwise(cb.nullLiteral(Int::class.java)),
            )
        }
        val prioritizedStatus: Expression<Int> =
            cb.function(
                "greatest",
                Int::class.java,
                maxIfStatusEquals(OfferStatus.SERVICE),
                maxIfStatusEquals(OfferStatus.RESERVED),
                maxIfStatusEquals(OfferStatus.OPTION_WAITING),
                maxIfStatusEquals(OfferStatus.OPTION),
                cb.literal(OfferStatus.FREE.value),
            )

        cq.multiselect(
            root.get<Long>("id"),
            root.get<String>("yachtName"),
            root.get<VesselType>("vesselType"),
            root.get<Short>("buildYear"),
            root.get<Short>("maxPersons"),
            root.get<Short>("cabins"),
            root.get<BigDecimal>("length"),
            root.get<String>("modelName"),
            root.get<String>("manufacturerName"),
            root.get<Long>("mainImage"),
            root.get<String>("agencyName"),
            root.get<EntryType>("entryType"),
            cb.countDistinct(root.get<Long>("totalLocations")).alias("sumLocations"),
            cb.least(root.get<CharterType>("charterType")),
            cb.least(root.get<String>("locationFullName")),
            // Pickup and drop-off can differ for one-way charters. We
            // aggregate independently — for multi-offer yachts the pair
            // may come from different rows, but the mapper still hides
            // `locationTo` whenever it equals `locationFullName` (most
            // common case), so the only visible cost is rare mismatched
            // labels for multi-offer one-way yachts.
            cb.least(root.get<String>("locationToFullName")),
            // Price columns are PER-DAY in the view and the card renders per-day × days.
            // Source clientPrice/listPrice/commission/days all from the SAME offer — the one
            // whose dates match the searched period (fallback: MIN across matches) — so the
            // per-day rate and the day-count never come from different-duration offers.
            exactPeriodOrMin(cb, root.get<BigDecimal>("clientPrice"), root.get("dateFrom"), root.get("dateTo"), searchParams.startDate, searchParams.endDate),
            exactPeriodOrMin(cb, root.get<BigDecimal>("listPrice"), root.get("dateFrom"), root.get("dateTo"), searchParams.startDate, searchParams.endDate),
            exactPeriodOrMin(cb, root.get<BigDecimal>("brokerCommission"), root.get("dateFrom"), root.get("dateTo"), searchParams.startDate, searchParams.endDate),
            exactPeriodDaysOrMin(cb, root.get<Int>("numberOfDays"), root.get("dateFrom"), root.get("dateTo"), searchParams.startDate, searchParams.endDate),
            // `prioritizedStatus` already aggregates per status via MAX(CASE);
            // GREATEST combines them, so no outer cb.max wrap.
            prioritizedStatus,
            // Prefer offer dates that exactly match the user's requested range,
            // so yachts with both a spot-on Sat-Sat offer AND a neighbouring
            // Thu-Thu one render as "Available" instead of "Closest day". If no
            // exact match exists we fall back to the earliest overlapping offer,
            // which is enough for the badge to show a sensible alternative.
            exactOrEarliest(cb, root.get<LocalDate>("dateFrom"), searchParams.startDate),
            exactOrEarliest(cb, root.get<LocalDate>("dateTo"), searchParams.endDate),
        )

        val predicates =
            buildYachtSearchPredicates(
                cq,
                cb,
                root,
                searchParams,
            )

        cq.where(*predicates.toTypedArray())

        cq.groupBy(
            root.get<Long>("id"),
            root.get<String>("yachtName"),
            root.get<VesselType>("vesselType"),
            root.get<Short>("buildYear"),
            root.get<Short>("maxPersons"),
            root.get<Short>("cabins"),
            root.get<BigDecimal>("length"),
            root.get<String>("modelName"),
            root.get<String>("manufacturerName"),
            root.get<Long>("mainImage"),
            root.get<String>("agencyName"),
            root.get<EntryType>("entryType"),
        )

        // FE sends a simple sortBy string; each branch below maps to the column
        // actually used for ORDER BY. Anything unrecognized falls back to the
        // "Recommended" sort (default for the empty-string sortBy on the
        // Recommended tab).
        //
        // Price-based sorts use TOTAL (per-day × duration), not the per-day value
        // the view exposes. Otherwise a 10-day Sunreef 60 at €4,800/day (€48,000
        // total) sits below a 7-day Lagoon 60 at €5,350/day (€37,460 total) —
        // which is wrong because the card displays totals, not day rates.
        val totalPriceExpr =
            cb.prod(
                root.get<BigDecimal>("clientPrice"),
                cb.toBigDecimal(root.get<Int>("numberOfDays")),
            )

        // Recommended-agency boost: only the "Recommended" tab promotes
        // curated partners' yachts to the top. The other tabs (price asc/desc,
        // length asc/desc, lowestPrepayment) honour the user's chosen sort
        // verbatim and do NOT mix the boost in — keeping each tab predictable.
        // agencyRecommended is exposed by yacht_search_view as 0/1 INT (V1_67)
        // so MAX() over the per-offer group keeps the value intact (every row
        // in a yacht's group shares the same agency).
        val recommendedBoost = cb.max(root.get<Int>("agencyRecommended"))
        when (sortBy) {
            "asc" -> {
                cq.orderBy(cb.asc(cb.min(totalPriceExpr)))
            }

            "desc" -> {
                cq.orderBy(cb.desc(cb.min(totalPriceExpr)))
            }

            "lowestPrepayment" -> {
                cq.orderBy(cb.asc(cb.min(root.get<BigDecimal>("lowestPrepayment"))))
            }

            "lengthAsc" -> {
                // Yachts without length land at the end — COALESCE maps NULL to a
                // large value so ascending order pushes them to the bottom. Postgres'
                // default ASC already does this; the COALESCE makes it explicit and
                // dialect-independent.
                cq.orderBy(cb.asc(cb.coalesce(root.get<BigDecimal>("length"), cb.literal(BigDecimal.valueOf(9999)))))
            }

            "lengthDesc" -> {
                // Default Postgres DESC puts NULLs first, which surfaces yachts
                // with no length as the "longest" — wrong. Map NULL to -1 so they
                // fall to the bottom while real lengths still sort largest-first.
                cq.orderBy(cb.desc(cb.coalesce(root.get<BigDecimal>("length"), cb.literal(BigDecimal.valueOf(-1)))))
            }

            "recommendedScore", "recommended" -> {
                // Curated partners first (DESC on the 0/1 boost ⇒ 1s before 0s),
                // then cheapest within each bucket. The legacy
                // `recommended_score` column is left in the view but no longer
                // drives this sort.
                cq.orderBy(cb.desc(recommendedBoost), cb.asc(cb.min(totalPriceExpr)))
            }

            else -> {
                // Empty / unknown sortBy ⇒ same behaviour as the Recommended tab.
                cq.orderBy(cb.desc(recommendedBoost), cb.asc(cb.min(totalPriceExpr)))
            }
        }

        val query = entityManager.createQuery(cq)
        val cappedSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val pageable = Pageable.ofSize(cappedSize).withPage(page)
        query.firstResult = pageable.offset.toInt()
        query.maxResults = pageable.pageSize

        val results = query.resultList

        // Bulk-fetch top-N amenity label_codes for yachts on THIS page only so
        // the card can render real icons instead of the previous hardcoded FE
        // fallback. Single extra query per page; scales with page size, not
        // with total yacht count.
        val amenityKeysByYachtId = fetchTopAmenities(results.map { it.id }, 3)

        // Bulk-fetch option expiry timestamps for the optioned yachts on
        // this page — one extra query regardless of page size. Options
        // come from `external_reservations`, populated by MMK + Nausys
        // availability sync. A yacht can have more than one overlapping
        // option row in theory (yacht swap mid-option, partner quirk); we
        // pick the SOONEST expiry so the broker sees the most-urgent
        // deadline. Skipped entirely when no yachts on the page are
        // optioned — keeps the query cheap for the common case.
        val optionedIds = results
            .filter { it.offerStatus == 2 || it.offerStatus == 3 }
            .map { it.id }
        val searchStart = searchParams.startDate
        val searchEnd = searchParams.endDate
        val optionExpiryByYachtId: Map<Long, java.time.LocalDateTime> =
            if (optionedIds.isNotEmpty() && searchStart != null && searchEnd != null) {
                externalReservationRepository
                    .findOptionsByYachtIdsAndPeriod(
                        optionedIds,
                        hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus.OPTION,
                        searchStart,
                        searchEnd,
                    )
                    .asSequence()
                    .mapNotNull { r ->
                        val yachtId = r.yacht?.id ?: return@mapNotNull null
                        val expiry = r.optionExpiration ?: return@mapNotNull null
                        yachtId to expiry
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, expiries) -> expiries.min() }
            } else {
                emptyMap()
            }

        val searchResponseDtos =
            results.map { view ->
                // isOption requires BOTH the aggregated offerStatus to flag
                // OPTION(2) / OPTION_WAITING(3) AND a still-live external
                // reservation backing it. Partner sync can leave offer.status
                // stuck at OPTION months after the actual option lapsed (the
                // sync only writes the OPTION snapshot; nothing clears it on
                // its own). Without this gate the listing would stamp a fake
                // "Under option" badge on yachts that are actually free —
                // matching what /yacht/.../offers (live reads) already shows.
                val isOption =
                    (view.offerStatus == 2 || view.offerStatus == 3) &&
                        optionExpiryByYachtId[view.id] != null

                yachtMapper.toDto(
                    view,
                    searchParams.currency,
                    language,
                    isOption,
                    amenityKeysByYachtId[view.id],
                    optionExpiryByYachtId[view.id],
                )
            }

        val total = getYachtSearchTotalCount(searchParams)

        return PageImpl(searchResponseDtos, pageable, total)
    }

    /**
     * Returns up to [limit] Equipment.labelCode strings per yacht. Results are
     * ranked by [CARD_AMENITY_PRIORITY] (customer-facing "what drives bookings"
     * order) first, then by Equipment.filterOrder for amenities not in the
     * priority list — so every yacht gets a consistent top-3 even when it
     * lacks several priority items.
     *
     * Equipment rows with no labelCode are skipped (can't be rendered).
     */
    private fun fetchTopAmenities(
        yachtIds: List<Long>,
        limit: Int,
    ): Map<Long, List<String>> {
        if (yachtIds.isEmpty()) return emptyMap()

        @Suppress("UNCHECKED_CAST")
        val rows =
            entityManager
                .createQuery(
                    """
                    SELECT ye.yachtId, e.labelCode, e.filterOrder
                    FROM YachtEquipment ye
                    JOIN ye.equipment e
                    WHERE ye.yachtId IN :yachtIds
                      AND e.labelCode IS NOT NULL
                    """.trimIndent(),
                ).setParameter("yachtIds", yachtIds)
                .resultList as List<Array<Any?>>

        // Pre-compute lookup so the comparator is O(1) per element.
        val priorityRank: Map<String, Int> =
            CARD_AMENITY_PRIORITY
                .withIndex()
                .associate { (idx, label) -> label to idx }

        return rows
            .groupBy { it[0] as Long }
            .mapValues { (_, list) ->
                list
                    .mapNotNull { row ->
                        val label = row[1] as? String ?: return@mapNotNull null
                        val filterOrder = (row[2] as? Short)?.toInt() ?: Int.MAX_VALUE
                        Triple(label, priorityRank[label] ?: Int.MAX_VALUE, filterOrder)
                    }.distinctBy { it.first }
                    // Priority items first (rank 0..N), then fall back to
                    // filter_order for anything else. Using MAX_VALUE as the
                    // default rank keeps non-priority items after priority ones.
                    .sortedWith(compareBy({ it.second }, { it.third }))
                    .take(limit)
                    .map { it.first }
            }
    }

    /**
     * Aggregation helper: returns the offer date that exactly matches [exact]
     * if any matching row has it, otherwise the earliest date across matches.
     * When [exact] is null (user didn't search with dates) we just return
     * `MIN(datePath)` like before.
     */
    private fun exactOrEarliest(
        cb: CriteriaBuilder,
        datePath: jakarta.persistence.criteria.Path<LocalDate>,
        exact: LocalDate?,
    ): Expression<LocalDate> {
        if (exact == null) return cb.least(datePath)
        val exactOnly =
            cb.selectCase<LocalDate>()
                .`when`(cb.equal(datePath, exact), datePath)
                .otherwise(cb.nullLiteral(LocalDate::class.java))
        // LEAST(CASE ...) returns `exact` if any row matches, else null (cb.min
        // is numeric-only; cb.least is the aggregate equivalent for Comparable).
        // Coalesce with LEAST(datePath) so we always get a date.
        return cb.coalesce(cb.least(exactOnly), cb.least(datePath))
    }

    /**
     * Aggregation helper for a per-offer numeric column (clientPrice/listPrice/commission/days):
     * returns the value FROM the offer whose dates exactly match the searched period, falling
     * back to MIN across all matching offers when there's no exact-period offer.
     *
     * Why: the matview's clientPrice/listPrice are PER-DAY and the card shows per-day × days.
     * Taking MIN(clientPrice) (cheapest per-day = the LONGEST offer) and MIN(numberOfDays)
     * (the SHORTEST offer) independently paired a long offer's day-rate with a short offer's
     * day-count — e.g. a 14-day offer's 162 €/day × 7 days = 1 136 € instead of the real 7-day
     * 2 037 €. Sourcing both from the same (exact-period) offer keeps per-day × days correct.
     */
    private fun exactPeriodOrMin(
        cb: CriteriaBuilder,
        value: Expression<BigDecimal>,
        dateFrom: Expression<LocalDate>,
        dateTo: Expression<LocalDate>,
        start: LocalDate?,
        end: LocalDate?,
    ): Expression<BigDecimal> {
        val minAll = cb.min(value)
        if (start == null || end == null) return minAll
        val exactOnly =
            cb.selectCase<BigDecimal>()
                .`when`(cb.and(cb.equal(dateFrom, start), cb.equal(dateTo, end)), value)
                .otherwise(cb.nullLiteral(BigDecimal::class.java))
        return cb.coalesce(cb.min(exactOnly), minAll)
    }

    private fun exactPeriodDaysOrMin(
        cb: CriteriaBuilder,
        value: Expression<Int>,
        dateFrom: Expression<LocalDate>,
        dateTo: Expression<LocalDate>,
        start: LocalDate?,
        end: LocalDate?,
    ): Expression<Int> {
        val minAll = cb.min(value)
        if (start == null || end == null) return minAll
        val exactOnly =
            cb.selectCase<Int>()
                .`when`(cb.and(cb.equal(dateFrom, start), cb.equal(dateTo, end)), value)
                .otherwise(cb.nullLiteral(Int::class.javaObjectType))
        return cb.coalesce(cb.min(exactOnly), minAll)
    }

    /**
     * Admin replacement-flow search — used when a yacht broke down / was
     * overbooked and the agency has already rebooked the same customer onto
     * a different boat in the partner system. Our availability sync has
     * marked that yacht UNAVAILABLE for the target week (or generated no
     * offer row at all because the full period is sold), so the regular
     * `getYachts` path doesn't return it.
     *
     * Goes through a native SQL path on the raw `yacht` + `offer` +
     * `external_reservations` tables instead of `yacht_search_view` so a
     * yacht that's fully-sold for the week still surfaces as long as the
     * partner has an overlapping `external_reservation` row for it.
     *
     * Price is the average per-day across the yacht's active offers in the
     * destination; null when the yacht has no offer at all (admin overrides
     * total price manually in the Create-Reservation wizard Step 2).
     */
    fun getYachtsForReplacement(
        searchParams: YachtSearchParamObject,
        language: LanguageEnum,
        page: Int,
        size: Int,
    ): PageImpl<YachtSearchResponseDto> {
        val locationIds =
            searchParams.locationIds
                ?.flatMap { getMarinas(it).mapNotNull { m -> m.id } }
                ?.distinct()
                .orEmpty()
        val agencyIds = searchParams.agencyIds.orEmpty()
        val vesselTypeValues = searchParams.vesselTypes?.map { it.name }.orEmpty()
        val startDate = searchParams.startDate ?: LocalDate.now()
        val endDate = searchParams.endDate ?: startDate.plusDays(7)
        val pageSize = size.coerceAtMost(MAX_PAGE_SIZE)
        val pageOffset = page * pageSize

        val rows =
            yachtRepository.findForReplacementSearch(
                locationIds = locationIds,
                locationIdsEmpty = locationIds.isEmpty(),
                agencyIds = agencyIds,
                agencyIdsEmpty = agencyIds.isEmpty(),
                vesselTypes = vesselTypeValues,
                vesselTypesEmpty = vesselTypeValues.isEmpty(),
                startDate = startDate,
                endDate = endDate,
                pageSize = pageSize,
                pageOffset = pageOffset,
            )
        val total =
            yachtRepository.countForReplacementSearch(
                locationIds = locationIds,
                locationIdsEmpty = locationIds.isEmpty(),
                agencyIds = agencyIds,
                agencyIdsEmpty = agencyIds.isEmpty(),
                vesselTypes = vesselTypeValues,
                vesselTypesEmpty = vesselTypeValues.isEmpty(),
                startDate = startDate,
                endDate = endDate,
            )

        val dtos = rows.map { toReplacementDto(it, searchParams.currency) }
        return PageImpl(dtos, org.springframework.data.domain.PageRequest.of(page, pageSize), total)
    }

    private fun toReplacementDto(
        row: ReplacementSearchRow,
        currency: CurrencyEnum,
    ): YachtSearchResponseDto {
        val vesselType = row.vesselType?.let { v -> VesselType.entries.firstOrNull { it.name == v } }
        val offerStatus =
            if (row.onlyExternalReservation) OfferStatus.UNAVAILABLE else OfferStatus.FREE
        val priceInfo = row.avgClientPrice?.let { exchangeRateCalculationService.calculatePriceInfo(it, currency) }
        return YachtSearchResponseDto(
            id = row.id,
            slug =
                SlugUtils.toSlugWithId(
                    row.manufacturerName,
                    row.modelName,
                    row.yachtName,
                    row.id,
                ),
            name = row.yachtName,
            location =
                row.locationId?.let {
                    LocationDto(
                        id = "l-$it",
                        name = row.locationName ?: "",
                        countryCode = row.locationCountry,
                    )
                },
            vesselType = vesselType,
            buildYear = row.buildYear,
            maxPersons = row.maxPersons,
            cabins = row.cabins,
            length = row.length,
            offerStatus = offerStatus,
            isOption = false,
            clientPriceEur = row.avgClientPrice,
            clientPriceInfo = priceInfo,
            modelName = row.modelName,
            mainImageId = row.mainImageId,
            agencyName = row.agencyName,
        )
    }

    fun getYachtSearchTotalCount(searchParams: YachtSearchParamObject): Long {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(Long::class.java)
        val root = cq.from(YachtSearchView::class.java)

        cq.select(cb.countDistinct(root))

        val predicates =
            buildYachtSearchPredicates(
                cq,
                cb,
                root,
                searchParams,
            )

        cq.where(*predicates.toTypedArray())
        return entityManager.createQuery(cq).singleResult
    }

    private fun buildYachtSearchPredicates(
        cq: CriteriaQuery<*>,
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
        searchParams: YachtSearchParamObject,
    ): List<Predicate> {
        val predicates = mutableListOf<Predicate>()

        // Resolve `did` (country / region / marina) into the matching marina
        // ids. Branch 1 yachts carry offer.location_from at marina granularity,
        // and branch 2 (custom) now also points to a marina via the admin
        // marina selector — so a single allMarinas IN-clause covers both.
        //
        // Earlier we tried adding the parent country/region realId alongside,
        // to surface custom yachts pinned at country level, but Country.id and
        // Location.id share the same BIGSERIAL space — adding `8` for `r-8`
        // accidentally matched Location.id=8 (ACI Marina Trogir) and pulled
        // unrelated Croatian yachts into Greek region searches. The marina
        // selector closes that gap at the data layer instead.
        val allMarinas =
            searchParams.locationIds
                ?.flatMap { locationId ->
                    getMarinas(locationId).mapNotNull { it.id }
                }?.distinct()
        if (!allMarinas.isNullOrEmpty()) {
            predicates.add(
                cb.or(
                    root.get<String>("locationFrom").`in`(allMarinas),
                    root.get<String>("locationTo").`in`(allMarinas),
                ),
            )
        }

        if (!searchParams.yachtIds.isNullOrEmpty()) {
            predicates.add(
                root.get<Long>("id").`in`(searchParams.yachtIds),
            )
        }

        // Offer-availability filter — hide `UNAVAILABLE=4` rows (owner weeks,
        // regattas, bookings that the agency already took outside our system)
        // from every caller UNLESS the admin "replacement flow" explicitly
        // asks for them. The view used to hard-code this filter; it's moved
        // here so the admin wizard can bypass it.
        if (!searchParams.includeUnavailable) {
            // offer_status is varchar (STRING enum) since V1_90 — compare against
            // the enum value, not the legacy ordinal `4`.
            predicates.add(cb.notEqual(root.get<OfferStatus>("offerStatus"), OfferStatus.UNAVAILABLE))
        }

        if (!searchParams.charterTypes.isNullOrEmpty()) {
            predicates.add(root.get<CharterType>("charterType").`in`(searchParams.charterTypes))
        }

        if (!searchParams.vesselTypes.isNullOrEmpty()) {
            predicates.add(root.get<VesselType>("vesselType").`in`(searchParams.vesselTypes))
        }

        if (!searchParams.agencyIds.isNullOrEmpty()) {
            // Admin filter — "show me only yachts operated by these agencies".
            // YachtSearchView carries `agency_id` as a direct column, no join
            // needed.
            predicates.add(root.get<Long>("agencyId").`in`(searchParams.agencyIds))
        }

        // Country scope. An explicit countryCodes whitelist (sitemap) wins; otherwise derive
        // it from any REGION ids in `did` so a region search only returns yachts in that
        // region's OWN country. Fixes cross-country partner regions: MMK's "Ionian" spans the
        // whole sea (Greek + Italian + Albanian coast), so a Greece > Ionian search used to
        // surface Taranto/Brindisi boats. RIGHT() over location_full_name sidesteps the JOIN +
        // Pageable bug; null-country regions contribute nothing so the filter quietly no-ops.
        val effectiveCountryCodes =
            searchParams.countryCodes?.takeIf { it.isNotEmpty() }
                ?: deriveRegionCountryCodes(searchParams.locationIds)
        if (effectiveCountryCodes.isNotEmpty()) {
            val codeUpper = effectiveCountryCodes.map { it.uppercase() }
            val countryCodeExpr = cb.function(
                "right",
                String::class.java,
                root.get<String>("locationFullName"),
                cb.literal(2),
            )
            predicates.add(countryCodeExpr.`in`(codeUpper))
        }

        if (!searchParams.manufacturers.isNullOrEmpty()) {
            predicates.add(root.get<Manufacturer>("manufacturerId").`in`(searchParams.manufacturers))
        }

        if (!searchParams.models.isNullOrEmpty()) {
            predicates.add(root.get<Model>("modelId").`in`(searchParams.models))
        }

        if (!searchParams.mainSailTypes.isNullOrEmpty()) {
            predicates.add(root.get<SailTypeEnum>("mainSailType").`in`(searchParams.mainSailTypes))
        }

        if (searchParams.minBuildYear != null && searchParams.maxBuildYear != null) {
            predicates.add(cb.between(root.get("buildYear"), searchParams.minBuildYear, searchParams.maxBuildYear))
        } else if (searchParams.minBuildYear != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("buildYear"), searchParams.minBuildYear))
        } else if (searchParams.maxBuildYear != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("buildYear"), searchParams.maxBuildYear))
        }

        if (searchParams.minPersons != null && searchParams.maxPersons != null) {
            predicates.add(cb.between(root.get("maxPersons"), searchParams.minPersons, searchParams.maxPersons))
        } else if (searchParams.minPersons != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("maxPersons"), searchParams.minPersons))
        } else if (searchParams.maxPersons != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("maxPersons"), searchParams.maxPersons))
        }

        if (searchParams.minCabins != null && searchParams.maxCabins != null) {
            predicates.add(cb.between(root.get("cabins"), searchParams.minCabins, searchParams.maxCabins))
        } else if (searchParams.minCabins != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("cabins"), searchParams.minCabins))
        } else if (searchParams.maxCabins != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("cabins"), searchParams.maxCabins))
        }

        if (searchParams.minBerths != null && searchParams.maxBerths != null) {
            predicates.add(cb.between(root.get("berths"), searchParams.minBerths, searchParams.maxBerths))
        } else if (searchParams.minBerths != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("berths"), searchParams.minBerths))
        } else if (searchParams.maxBerths != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("berths"), searchParams.maxBerths))
        }

        if (searchParams.minLength != null && searchParams.maxLength != null) {
            predicates.add(
                cb.between(
                    root.get("length"),
                    searchParams.getMinLengthInMeters(),
                    searchParams.getMaxLengthInMeters(),
                ),
            )
        } else if (searchParams.minLength != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("length"), searchParams.getMinLengthInMeters()))
        } else if (searchParams.maxLength != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("length"), searchParams.getMaxLengthInMeters()))
        }

        if (searchParams.minPrice != null && searchParams.maxPrice != null) {
            predicates.add(
                cb.between(
                    root.get("clientPrice"),
                    searchParams.getMinPriceInEur(exchangeRateCalculationService),
                    searchParams.getMaxPriceInEur(exchangeRateCalculationService),
                ),
            )
        } else if (searchParams.minPrice != null) {
            predicates.add(
                cb.greaterThanOrEqualTo(
                    root.get("clientPrice"),
                    searchParams.getMinPriceInEur(exchangeRateCalculationService),
                ),
            )
        } else if (searchParams.maxPrice != null) {
            predicates.add(
                cb.lessThanOrEqualTo(
                    root.get("clientPrice"),
                    searchParams.getMaxPriceInEur(exchangeRateCalculationService),
                ),
            )
        }

        // Flex by start day: offer passes only if its start sits within ±DATE_FLEX_DAYS
        // of the user's requested start. Durations past the user's end are fine
        // (e.g. 10-day Tue→Fri that starts 07.07. still matches a 04.07.→11.07.
        // search), but offers starting a full week later (11.07.→18.07.) are
        // already the NEXT week and would just clutter the results.
        // Custom yachts (entry_type=2 in yacht_search_view) carry NULL
        // dateFrom/dateTo because they have no offer rows — they're inquiry-
        // only listings that the admin maintains by hand. Without an OR
        // dateFrom IS NULL escape hatch, the date predicate would silently
        // exclude every custom yacht the moment a user picks a date, even
        // though the listing should always show ("contact us for this date").
        // Sort still works naturally — branch 2 emits client_price = lowPrice/7
        // and length = y.length, so cheapest/longest sorts pick custom yachts
        // up alongside external offers.
        val isCustomYacht = cb.isNull(root.get<LocalDate>("dateFrom"))
        if (searchParams.startDate != null && searchParams.endDate != null) {
            if (!searchParams.startDate.isBefore(searchParams.endDate)) {
                throw IllegalArgumentException("Starting date must be before end date")
            }
            predicates.add(
                cb.or(
                    isCustomYacht,
                    cb.and(
                        cb.greaterThanOrEqualTo(root.get("dateFrom"), searchParams.startDate.minusDays(DATE_FLEX_DAYS)),
                        cb.lessThanOrEqualTo(root.get("dateFrom"), searchParams.startDate.plusDays(DATE_FLEX_DAYS)),
                    ),
                ),
            )
        } else if (searchParams.startDate != null) {
            predicates.add(
                cb.or(
                    isCustomYacht,
                    cb.and(
                        cb.greaterThanOrEqualTo(root.get("dateFrom"), searchParams.startDate.minusDays(DATE_FLEX_DAYS)),
                        cb.lessThanOrEqualTo(root.get("dateFrom"), searchParams.startDate.plusDays(DATE_FLEX_DAYS)),
                    ),
                ),
            )
        } else if (searchParams.endDate != null) {
            predicates.add(
                cb.or(
                    isCustomYacht,
                    cb.and(
                        cb.greaterThanOrEqualTo(root.get("dateTo"), searchParams.endDate.minusDays(DATE_FLEX_DAYS)),
                        cb.lessThanOrEqualTo(root.get("dateTo"), searchParams.endDate.plusDays(DATE_FLEX_DAYS)),
                    ),
                ),
            )
        }

        if (searchParams.minWc != null && searchParams.maxWc != null) {
            predicates.add(cb.between(root.get("wc"), searchParams.minWc, searchParams.maxWc))
        } else if (searchParams.minWc != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("wc"), searchParams.minWc))
        } else if (searchParams.maxWc != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("wc"), searchParams.maxWc))
        }

        if (searchParams.minEnginePower != null && searchParams.maxEnginePower != null) {
            predicates.add(
                cb.between(
                    root.get("enginePower"),
                    searchParams.minEnginePower,
                    searchParams.maxEnginePower,
                ),
            )
        } else if (searchParams.minEnginePower != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("enginePower"), searchParams.minEnginePower))
        } else if (searchParams.maxEnginePower != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("enginePower"), searchParams.maxEnginePower))
        }

        if (!searchParams.amenities.isNullOrEmpty()) {
            val countSubquery = cq.subquery(Long::class.java)
            val yachtEquipmentRoot = countSubquery.from(YachtEquipment::class.java)

            countSubquery
                .select(cb.countDistinct(yachtEquipmentRoot.get<Long>("id")))
                .where(
                    cb.and(
                        cb.equal(yachtEquipmentRoot.get<Long>("yachtId"), root.get<Long>("id")),
                        yachtEquipmentRoot.get<Long>("equipmentId").`in`(searchParams.amenities),
                    ),
                )

            predicates.add(
                cb.equal(countSubquery, searchParams.amenities.size.toLong()),
            )
        }

        if (!searchParams.services.isNullOrEmpty()) {
            val countSubquery = cq.subquery(Long::class.java)
            val yachtExtraRoot = countSubquery.from(YachtExtra::class.java)

            countSubquery
                .select(cb.countDistinct(yachtExtraRoot.get<Long>("extrasId")))
                .where(
                    cb.and(
                        cb.equal(yachtExtraRoot.get<Long>("yachtId"), root.get<Long>("id")),
                        yachtExtraRoot.get<Long>("extrasId").`in`(searchParams.services),
                    ),
                )

            predicates.add(
                cb.equal(countSubquery, searchParams.services.size.toLong()),
            )
        }

        return predicates
    }

    private fun getMarinas(locationId: String): List<Location> {
        val locationType =
            when (locationId.first()) {
                'r' -> LocationType.REGION
                'c' -> LocationType.COUNTRY
                'l' -> LocationType.MARINA
                else -> return emptyList()
            }

        val id = locationId.substring(2).toIntOrNull() ?: return emptyList()

        return when (locationType) {
            // A marina can exist twice (one row per provider, spelled differently —
            // "Marina Kastela" vs "Marina Kaštela"); pull every same-place sibling so the
            // search returns BOTH fleets, not just the picked id's.
            LocationType.MARINA -> {
                val marina = locationRepository.findById(id.toLong()).orElse(null)
                when {
                    marina == null -> emptyList()
                    marina.name.isNullOrBlank() -> listOf(marina)
                    else ->
                        locationRepository
                            .findMarinasByFoldedName(marina.name!!, marina.countryCode)
                            .ifEmpty { listOf(marina) }
                }
            }
            LocationType.COUNTRY -> locationRepository.findMarinasByCountryId(id)
            LocationType.REGION -> locationRepository.findMarinasByRegionId(id)
        }
    }

    /**
     * Country codes of the REGION ids (`r-…`) in a `did` list. Used to scope a region search
     * to the region's own country so cross-country partner regions don't leak foreign-country
     * yachts (e.g. MMK's "Ionian" covering both the Greek islands and the Italian/Albanian
     * coast). Country (`c-…`) and marina (`l-…`) ids are ignored — already single-country at
     * the marina layer. Returns distinct non-null codes; empty when there are no region ids or
     * their country_code is unset, in which case the caller applies no country filter.
     */
    private fun deriveRegionCountryCodes(locationIds: List<String>?): List<String> =
        locationIds
            ?.filter { it.firstOrNull() == 'r' }
            ?.mapNotNull { it.substring(2).toLongOrNull() }
            ?.mapNotNull { regionRepository.findById(it).orElse(null)?.countryCode }
            ?.distinct()
            .orEmpty()

    fun getYacht(
        id: Long,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        currency: CurrencyEnum?,
        language: LanguageEnum,
    ): YachtDetailsDto {
        val yacht = getValidYacht(id)

        val offerDto =
            if (dateFrom != null && dateTo != null) {
                val offers = offerRepository.findAllByYachtAndDateFromAndDateTo(yacht, dateFrom!!, dateTo!!)
                offers.map { offerMapper.toDto(it, currency) }
            } else {
                // SEO / canonical URL with no date filter: still load the
                // next 12 months of offers so the detail page's Product
                // JSON-LD can populate an `offers` AggregateOffer field.
                // Google Search Console flags Product structured data
                // without offers / review / aggregateRating as a critical
                // issue (2026-05-28). The controller-level partner sync
                // trigger is intentionally NOT re-fired here (still keyed
                // on `dateFrom != null` in YachtController) so canonical-
                // URL hits don't initiate per-request external round-
                // trips — we serve whatever the periodic catalogue sync
                // has already loaded.
                val today = LocalDate.now()
                val offers = offerRepository.findAllByYachtAndDateFromGreaterThanEqualAndDateToLessThanEqual(
                    yacht,
                    today,
                    today.plusYears(1),
                )
                offers.map { offerMapper.toDto(it, currency) }
            }

        val agencyId = yacht.agency?.id
        val locationId = yacht.location?.id
        val yachtExtras =
            if (yacht.entryType == EntryType.EXTERNAL && agencyId != null && locationId != null) {
                val externalBasesExternalIds =
                    externalBaseRepository
                        .findByAgencyIdAndLocationId(agencyId, locationId)
                        .map { it.externalId!! }
                val yachtExtraIds =
                    yachtExtraRepository.findYachtExtraIdsGroupedByYacht(
                        yacht.id!!,
                        dateFrom,
                        dateTo,
                        externalBasesExternalIds.toTypedArray(),
                    )
                yachtExtraRepository.findGroupedByYacht(yacht, yachtExtraIds)
            } else {
                // Legacy data: some external yachts lack a location (Sea Dreams
                // id=3117, DESSUS id=3116, Fortuna 5533, ...). Without a
                // location we can't run the agency-base scoped grouping query,
                // but we STILL need the sailing-window filter (otherwise
                // period-specific APA / packs — MMK splits seasonal pricing
                // into rows with disjoint sailing dates — all render for
                // every booking window, flagged by Mario 23.4.2026 on
                // Fortuna 5533).
                val yachtExtraIds = yachtExtraRepository.findYachtExtraIdsByYachtAndPeriod(
                    yacht.id!!,
                    dateFrom,
                    dateTo,
                )
                // Hibernate on empty list for `IN :ids` is unpredictable
                // across versions; short-circuit to avoid that edge case.
                if (yachtExtraIds.isEmpty()) emptyList()
                else yachtExtraRepository.findGroupedByYacht(yacht, yachtExtraIds)
            }

        val result =
            yachtMapper.toDetailsDto(
                yacht,
                offerDto,
                yachtExtras,
                currency,
                language,
            )

        return result
    }

    fun getYachtAvailability(
        id: Long,
        month: Int?,
        year: Int,
    ): List<YachtAvailabilityDto> {
        getValidYacht(id) // verify if yacht and agency are active, if not exception is thrown

        val reservations =
            if (month == null) {
                externalReservationRepository.findYachtAvailabilityByYear(id, year)
            } else {
                val startDate = LocalDate.of(year, month, 1)
                val endDate = startDate.withDayOfMonth(startDate.lengthOfMonth())
                externalReservationRepository.findYachtAvailabilityByAdjustedYearAndMonth(id, startDate, endDate)
            }

        val yachtAvailability =
            reservations.map {
                it.toYachtAvailabilityDto()
            }

        return yachtAvailability
    }

    @Cacheable("usedVesselTypesCache")
    fun getUsedVesselTypes(): List<VesselType> {
        return yachtRepository
            .getUsedVesselTypes()
            .map { VesselType.entries[it] }
    }

    @Cacheable("vesselTypeYachtCountCache")
    fun getVesselTypeYachtCount(): List<VesselTypeYachtCountDto> {
        return yachtRepository.getVesselTypeYachtCount().map { row ->
            VesselTypeYachtCountDto(
                vesselType = row[0] as VesselType,
                yachtCount = (row[1] as Number).toInt(),
            )
        }
    }

    @Cacheable("usedCharterTypesCache")
    fun getUsedCharterTypes(): List<CharterType> {
        return yachtRepository
            .getUsedCharterTypes()
            .map { CharterType.entries[it] }
    }

    fun getCustomYachts(
        name: String?,
        pageable: Pageable,
    ): Page<CustomYachtResponse> {
        return if (name.isNullOrBlank()) {
            customYachtViewRepository.findAll(pageable).map { yachtMapper.toDto(it) }
        } else {
            customYachtViewRepository.findAllByNameLikeIgnoreCase(name.trim(), pageable).map { yachtMapper.toDto(it) }
        }
    }

    fun getCustomYachtDetails(id: Long): CustomYachtDetailsResponse {
        val yacht =
            yachtRepository
                .findById(id)
                .orElseThrow { YachtDoesNotExistException() }
        val customYachtDetails =
            customYachtDetailRepository.findByYachtId(id)
                ?: throw YachtDoesNotExistException()
        val translations = yachtTranslationRepository.findAllByYachtId(id)

        return yachtMapper.toCustomYachtDetailsResponse(yacht, customYachtDetails, translations)
    }

    fun getCustomBoatBrochure(yachtId: Long): Resource {
        yachtRepository
            .findById(yachtId)
            .orElseThrow { YachtDoesNotExistException() }
        val customYachtDetails =
            customYachtDetailRepository.findByYachtId(yachtId)
                ?: throw YachtDoesNotExistException()

        val pdfPath = fileSystemService.getResourcePath(customYachtDetails.pdfUrl!!)

        return fileSystemService.getResourceFromPath(pdfPath)
    }

    private fun getValidYacht(yachtId: Long): Yacht {
        val yacht =
            yachtRepository
                .findById(yachtId)
                .orElseThrow { YachtDoesNotExistException() }

        if (!yacht.sysActive!!) {
            throw YachtNotActiveException()
        }

        val agency = yacht.agency
        if (yacht.entryType == EntryType.EXTERNAL && (agency == null || !agency.active!!)) {
            throw AgencyNotActiveException()
        }

        return yacht
    }
}
