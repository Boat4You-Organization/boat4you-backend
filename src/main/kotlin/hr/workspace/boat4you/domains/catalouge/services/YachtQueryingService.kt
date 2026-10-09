package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityColumns
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtDetailsResponse
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtResponse
import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterDto
import hr.workspace.boat4you.domains.catalouge.dto.VesselTypeYachtCountDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtAvailabilityDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtDetailsDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchResponseDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.enums.MatchKind
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.catalouge.jpa.ReplacementSearchRow
import hr.workspace.boat4you.domains.catalouge.utils.GuletRules
import hr.workspace.boat4you.domains.catalouge.utils.OfferCharterRules
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyNotActiveException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtNotActiveException
import hr.workspace.boat4you.domains.catalouge.successor.YachtSuccessor
import hr.workspace.boat4you.domains.catalouge.successor.YachtSuccessorLookup
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
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
import hr.workspace.boat4you.domains.catalouge.jpa.YachtListingTwin
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
import jakarta.persistence.criteria.Order
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.hibernate.query.criteria.HibernateCriteriaBuilder
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.core.io.Resource
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

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
    private val countryRepository: CountryRepository,
    private val heavyQueries: HeavyQueryRunner,
) {
    companion object {
        private const val MAX_PAGE_SIZE = 100

        /** A standard charter week (Sat→Sat). Multi-week covering-sum pricing only kicks in for
         *  requests LONGER than one week — a 7-night request has a single covering offer. */
        private const val WEEK_NIGHTS = 7

        /**
         * Undated weekly "from" price (priceBasis=week): the cheapest bookable week is only
         * trusted when it is at least this share of the yacht's dearest bookable week. Partner
         * typos drop a zero (Bavaria Cruiser 46 "Leonidas II", 25.9.2026: one FREE week at
         * 294.50 € among weeks of 1,736–4,940 €, ratio 0.06); a real low/peak season spread
         * stays well above it (the same yacht: 0.35). Below it the card says "price on request"
         * instead of advertising a price nobody can book.
         */
        private val WEEKLY_PRICE_OUTLIER_RATIO = BigDecimal("0.12")

        /**
         * An offer priced below 300 EUR a week (per night: 300 / 7, for an offer of ANY length) is a partner
         * placeholder (Valencia 26.9.2026: whole grids of "10 EUR" weeks, 1.49 EUR a day), never a real price: it is
         * not a weekly "from" price candidate, not a dated card's price (the card says "price on request"), not a
         * week of a multi-week tiling and not the undated default path's price. The charter facts use the same floor
         * (CharterFactsMath.MIN_WEEK_PRICE). Compared per day, the matview's unit.
         */
        private val MIN_WEEK_PRICE_PER_DAY: BigDecimal = BigDecimal(300).divide(BigDecimal(WEEK_NIGHTS), 10, java.math.RoundingMode.HALF_UP)

        /**
         * [offerChoiceKey] tiers, best first: the exact searched period; the searched length at the nearest start;
         * an offer covering the searched nights; any other offer; a row without a positive price (never shown as a
         * price).
         */
        private const val CHOICE_EXACT = "0"
        private const val CHOICE_SAME_LENGTH = "1"
        private const val CHOICE_COVERS = "2"
        private const val CHOICE_OTHER = "3"
        private const val CHOICE_NO_PRICE = "9"

        /** Fixed-width parts of the choice key: tier(1) distance(4) from(8) to(8) client total(15) |list|commission. */
        private const val KEY_DISTANCE_WIDTH = 4
        private const val KEY_DISTANCE_FORMAT = "FM0000"
        private const val KEY_TOTAL_START = 22
        private const val KEY_TOTAL_WIDTH = 15
        private const val KEY_TOTAL_FORMAT = "FM000000000000.00"
        private const val KEY_TOTAL_PARSE = "000000000000.00"
        private const val NO_DATE = "00000000"

        /**
         * Honest "shifted week" reach (Deploy 4). A published offer slot
         * qualifies if its OWN window overlaps [from - N, to + N] — judged on
         * the slot's real dateFrom/dateTo, NOT a start-day clamp. So a 04.07.–
         * 11.07. (Sat–Sat) search also surfaces a genuinely-nearby Thu–Thu or
         * Mon–Mon published week (labelled "closest week"), but never a slot
         * whose interval doesn't actually reach the requested period. Small (3)
         * so it stays honest.
         */
        private const val NEARBY_WINDOW_DAYS = 3L

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

        /**
         * Throttle for the unresolvable-`did` WARN (16.9.2026 cusma2 load incident). The refusal
         * fires per request, and a crawler that found a stale landing page sends thousands of
         * them, so log once a minute with the running total instead of one line each.
         */
        private const val DID_REFUSAL_WARN_INTERVAL_MS = 60_000L

        /** Enough tokens to identify the offending URL without printing a whole crawler's list. */
        private const val DID_REFUSAL_LOGGED_TOKENS = 5

        /** `did` tokens are `c-54` / `r-12` / `l-9001`; anything else in them came from the query
         *  string and must not reach the log as-is (a CRLF would forge a line). */
        private val UNSAFE_DID_CHARS = Regex("[^A-Za-z0-9_-]")
    }

    private val log = LoggerFactory.getLogger(this::class.java)
    private val didRefusals = AtomicLong()
    private val lastDidWarnAtMs = AtomicLong()

    // Cache the heaviest query in the system — the search listing page. It's a
    // criteria query over the 380MB yacht_search_view with GROUP BY + status
    // GREATEST/CASE + a correlated NOT EXISTS availability check; at peak many
    // users repeat the same popular searches (top destinations, Sat-Sat weeks,
    // default sort, page 0) and each re-run contends for the 2 DB cores. A short
    // TTL collapses the repeats. Key = full YachtSearchParamObject (data class,
    // includes currency + language + every filter) + sortBy + language + page +
    // size, so two requests differing in ANY of those never share an entry (no
    // wrong-currency/-language results). The controller still fires the on-demand
    // syncYachtOffers BEFORE calling this (cache is at the service layer), so
    // partner availability sync is never skipped. The admin replacement flow uses
    // getYachtsForReplacement (a different method) and stays uncached. See
    // CacheConfig.yachtSearchListCache for the 2-min TTL / booking-safety note.
    //
    // SECURITY: `condition = "!#isAdmin"` — admin requests are NEVER cached.
    // YachtMapper gates agencyName + agencyCommissionEur (broker commission) on
    // SecurityContextHolder.isAdminUser(), so an admin's result carries sensitive
    // figures a customer must not see. Since the cache key does NOT include the
    // caller's role, caching an admin result and serving it to a customer (same
    // filters) would leak commission. Bypassing the cache for admins keeps those
    // results out of the cache entirely; only non-admin results (agencyName=null,
    // commission=null) are ever stored, so they are safe to share across users.
    // `isAdmin` is computed in the controller via the same SYSTEM_ADMIN authority
    // the mapper checks. Admin search volume is tiny, so no CPU cost to skipping.
    //
    // 1.10.2026 (Codex audit F2): a miss runs through HeavyQueryGuard — at most
    // `application.heavy-queries.search-list.max-concurrent` listings at once, the rest
    // 503 + Retry-After, in the guard's own read-only transaction with a statement
    // timeout. NOT_SUPPORTED overrides the class-level transaction, so a cache hit or a
    // shed request never takes a pool connection (a burst of cold landing pages used
    // to empty the pool and starve the boat pages). Admin searches (the Create
    // Reservation modal, the Offers workspace) skip the gate — same transaction and
    // timeouts, never shed with a public burst.
    @Cacheable(
        cacheNames = ["yachtSearchListCache"],
        key = "{#searchParams, #sortBy, #language, #page, #size}.toString()",
        condition = "!#isAdmin",
    )
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun getYachts(
        searchParams: YachtSearchParamObject,
        sortBy: String?,
        language: LanguageEnum,
        page: Int,
        size: Int,
        isAdmin: Boolean,
    ): PageImpl<YachtSearchResponseDto> {
        val search = { searchYachts(searchParams, sortBy, language, page, size, isAdmin) }
        return if (isAdmin) heavyQueries.readUngated(HeavyQuery.SEARCH_LIST, search) else heavyQueries.read(HeavyQuery.SEARCH_LIST, search)
    }

    private fun searchYachts(
        searchParams: YachtSearchParamObject,
        sortBy: String?,
        language: LanguageEnum,
        page: Int,
        size: Int,
        isAdmin: Boolean,
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

        // Multi-week covering sums (see coveringPeriodTotal). Null-literal placeholders for a
        // dateless search so the multiselect arity stays fixed; the Kotlin side only trusts these
        // when a dated multi-week request fully tiles (coveringNights == requestedNights).
        val requestedNights: Int? =
            searchParams.startDate?.let { s ->
                searchParams.endDate?.let { e -> java.time.temporal.ChronoUnit.DAYS.between(s, e).toInt() }
            }
        val daysPath = root.get<Int>("numberOfDays")
        val clientPath = root.get<BigDecimal>("clientPrice")
        val dateFromPath = root.get<LocalDate>("dateFrom")
        val dateToPath = root.get<LocalDate>("dateTo")
        val coveringClientTotalExpr =
            coveringPeriodTotal(cb, root.get("clientPrice"), daysPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate, clientPath)
                ?: cb.nullLiteral(BigDecimal::class.java)
        val coveringListTotalExpr =
            coveringPeriodTotal(cb, root.get("listPrice"), daysPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate, clientPath)
                ?: cb.nullLiteral(BigDecimal::class.java)
        val coveringCommissionTotalExpr =
            coveringPeriodTotal(cb, root.get("brokerCommission"), daysPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate, clientPath)
                ?: cb.nullLiteral(BigDecimal::class.java)
        val coveringNightsExpr =
            coveringPeriodNights(cb, daysPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate, clientPath)
                ?: cb.nullLiteral(Int::class.javaObjectType)

        // Undated listing priced per WEEK (priceBasis=week, sent by the web's undated landings,
        // 25.9.2026): without dates the default MIN(per-day) and MIN(days) come from different
        // offers — Greece showed "1 day 211 €", "3 days 318 €" and "7 days 0 €" side by side,
        // cheapest first. In this mode every card carries one comparable figure: the cheapest
        // bookable 7-night offer (or none → the card says "price on request"). The page and the
        // count keep their filters; only the price columns and the price ordering change.
        val weeklyMode = searchParams.weeklyPrice && searchParams.startDate == null && searchParams.endDate == null
        val periodPrice: (Expression<BigDecimal>) -> Expression<BigDecimal> = { value ->
            if (weeklyMode) {
                weeklyFromValue(cb, root, value, BigDecimal::class.java)
            } else {
                exactPeriodOrMin(cb, value, clientPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate)
            }
        }
        val periodDays: () -> Expression<Int> = {
            if (weeklyMode) {
                weeklyFromValue(cb, root, daysPath, Int::class.javaObjectType)
            } else {
                exactPeriodDaysOrMin(cb, daysPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate)
            }
        }

        // The ONE offer a card shows (26.9.2026 audit B15). Dated: exact period, else the same length nearest the
        // searched start, else an offer covering the searched nights, else the nearest; its price, day count and dates
        // come from that same row. Weekly mode: the dates of the cheapest trusted week. See offerChoiceKey.
        val datedSearch = searchParams.startDate != null && searchParams.endDate != null
        val chosenKeyExpr: Expression<String> =
            when {
                datedSearch -> cb.least(offerChoiceKey(cb, root, searchParams.startDate!!, searchParams.endDate!!, requestedNights!!))
                weeklyMode -> cb.least(weeklyWeekKey(cb, root))
                else -> cb.nullLiteral(String::class.java)
            }

        // GROUP BY id ONLY (9.7.2026); every other selected yacht attribute is
        // functionally dependent on it (same yacht ⇒ same name/model/agency/…)
        // and gets wrapped in MIN()/LEAST() to stay valid SQL. Grouping by the
        // full 12-column tuple made the planner estimate ~161k groups (actual
        // ~4k) — it refused HashAggregate and fell back to a sorted full-view
        // index walk (~1.9s for one country page). With GROUP BY id the group
        // estimate is n_distinct(id) and the same query hash-aggregates the
        // bitmap-scanned country rows in ~0.5s.
        cq.multiselect(
            root.get<Long>("id"),
            cb.least(root.get<String>("yachtName")),
            cb.least(root.get<VesselType>("vesselType")),
            cb.min(root.get<Short>("buildYear")),
            cb.min(root.get<Short>("maxPersons")),
            cb.min(root.get<Short>("cabins")),
            // berths + wc: matview columns already, positional (YachtSearchSelectResult) - capacity contract v1
            cb.min(root.get<Short>("berths")),
            cb.min(root.get<Short>("wc")),
            cb.min(root.get<BigDecimal>("length")),
            cb.least(root.get<String>("modelName")),
            cb.least(root.get<String>("manufacturerName")),
            cb.min(root.get<Long>("mainImage")),
            cb.least(root.get<String>("agencyName")),
            cb.least(root.get<EntryType>("entryType")),
            // sumLocations RETIRED (9.7.2026): fed only a dead FE prop, and as
            // the sole COUNT(DISTINCT …) it forced sort-based grouping (blocks
            // HashAggregate). Null literal keeps the multiselect arity; the
            // mapper passes null through and no client renders it.
            cb.nullLiteral(Long::class.javaObjectType),
            // A gulet's BAREBOAT rows do not count (GuletRules: a gulet is never bareboat); a gulet with nothing else
            // reads null here and CREWED on the card. Every other row is its partner type, as before.
            cb.least(
                cb
                    .selectCase<CharterType>()
                    .`when`(isGuletBareboatRow(cb, root), cb.nullLiteral(CharterType::class.java))
                    .otherwise(root.get<CharterType>("charterType")),
            ),
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
            //
            // Undated + priceBasis=week (the web's destination landings): the cheapest bookable
            // 7-night offer instead — see weeklyFromValue.
            periodPrice(root.get<BigDecimal>("clientPrice")),
            periodPrice(root.get<BigDecimal>("listPrice")),
            periodPrice(root.get<BigDecimal>("brokerCommission")),
            periodDays(),
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
            coveringClientTotalExpr,
            coveringListTotalExpr,
            coveringCommissionTotalExpr,
            coveringNightsExpr,
            chosenKeyExpr,
        )

        val predicates =
            buildYachtSearchPredicates(
                cq,
                cb,
                root,
                searchParams,
            )

        cq.where(*predicates.toTypedArray())

        // See the multiselect comment: grouping by id alone (attributes are
        // functionally dependent + aggregate-wrapped) keeps the planner's
        // group estimate accurate so it picks HashAggregate.
        cq.groupBy(root.get<Long>("id"))

        // FE sends a simple sortBy string; each branch below maps to the column
        // actually used for ORDER BY. Anything unrecognized falls back to the
        // "Recommended" sort (default for the empty-string sortBy on the
        // Recommended tab).
        //
        // Price-based sorts use TOTAL (per-day × duration), not the per-day value
        // the view exposes. Otherwise a 10-day Sunreef 60 at €4,800/day (€48,000
        // total) sits below a 7-day Lagoon 60 at €5,350/day (€37,460 total) —
        // which is wrong because the card displays totals, not day rates.
        // Sort by the SAME total the card renders — the exact-searched-week
        // offer's per-day rate × its day-count (exactPeriodOrMin/…Days), the very
        // expressions selected for the displayed clientPrice/numberOfDays above.
        // The old min(clientPrice × numberOfDays) sorted by the yacht's CHEAPEST
        // matched week, so a yacht with a cheaper neighbouring week jumped above
        // one whose displayed (searched-week) price was actually lower → price-asc
        // looked broken (fix 7.6.2026). Already aggregated, so no outer cb.min.
        val exactDaysExpr = periodDays()
        val exactTotalExpr =
            cb.prod(
                periodPrice(root.get<BigDecimal>("clientPrice")),
                cb.toBigDecimal(exactDaysExpr),
            )
        // For a multi-week request that the weekly offers fully tile (and no exact-period offer
        // exists), order by the TRUE summed period total — the same number the card now shows —
        // so price-asc/desc stays consistent with the displayed price (the 7.6.2026 invariant).
        val totalPriceExpr: Expression<out Number> =
            if (datedSearch) {
                // The chosen offer's total — the figure the card shows — or the multi-week covering sum when the
                // weekly offers tile a longer request and no exact-period offer exists (applyCoveringPeriodPrice).
                val chosenTotal = chosenOfferTotal(cb, chosenKeyExpr)
                if (requestedNights!! > WEEK_NIGHTS) {
                    val coveringTotal =
                        coveringPeriodTotal(cb, root.get("clientPrice"), daysPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate, clientPath)!!
                    val coveringNights =
                        coveringPeriodNights(cb, daysPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate, clientPath)!!
                    cb.selectCase<BigDecimal>()
                        .`when`(
                            cb.and(
                                cb.equal(coveringNights, cb.literal(requestedNights)),
                                cb.notEqual(cb.substring(chosenKeyExpr, 1, 1), cb.literal(CHOICE_EXACT)),
                            ),
                            coveringTotal,
                        ).otherwise(chosenTotal)
                } else {
                    chosenTotal
                }
            } else if (requestedNights != null && requestedNights > WEEK_NIGHTS) {
                val coveringTotal =
                    coveringPeriodTotal(cb, root.get("clientPrice"), daysPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate, clientPath)!!
                val coveringNights =
                    coveringPeriodNights(cb, daysPath, dateFromPath, dateToPath, searchParams.startDate, searchParams.endDate, clientPath)!!
                cb.selectCase<BigDecimal>()
                    .`when`(
                        cb.and(
                            cb.equal(coveringNights, cb.literal(requestedNights)),
                            cb.notEqual(exactDaysExpr, cb.literal(requestedNights)),
                        ),
                        coveringTotal,
                    )
                    .otherwise(exactTotalExpr)
            } else {
                exactTotalExpr
            }

        // Recommended-agency boost: only the "Recommended" tab promotes
        // curated partners' yachts to the top. The other tabs (price asc/desc,
        // length asc/desc, lowestPrepayment) honour the user's chosen sort
        // verbatim and do NOT mix the boost in — keeping each tab predictable.
        // agencyRecommended is exposed by yacht_search_view as 0/1 INT (V1_67)
        // so MAX() over the per-offer group keeps the value intact (every row
        // in a yacht's group shares the same agency).
        val recommendedBoost = cb.max(root.get<Int>("agencyRecommended"))
        // Every sort ends on the yacht id (the GROUP BY key, unique per result row) so the ORDER BY
        // is a total order. Without it a page boundary that falls inside a run of tied keys —
        // hundreds of yachts share a searched-week total, a deposit or a length — is cut at a
        // different place on each LIMIT/OFFSET: Postgres' bounded top-N heap sort orders equal keys
        // differently for each bound, so a yacht came back on two neighbouring pages while another
        // came back on none (22.9.2026: Croatia catamarans 897 rows / 894 distinct over 9 pages;
        // the admin Offers walk reported the 3 missing). Appended after the `when` so no branch can
        // forget it; see YachtSearchPagingStabilityTest.
        // Dated searches rank the cards that match the searched length (exact or shifted) before the longer / other
        // offers, and cards without a price last — a 7-night price is never ranked among 3-night prices (audit B15).
        val lengthGroup: List<Order> = if (datedSearch) listOf(cb.asc(choiceGroup(cb, chosenKeyExpr))) else emptyList()
        val primaryOrders: List<Order> =
            when (sortBy) {
                "asc" -> {
                    lengthGroup + cb.asc(totalPriceExpr)
                }

                "desc" -> {
                    // A yacht without a price (weekly mode: no bookable week; default path: no positive
                    // offer) is NULL; Postgres puts NULLs FIRST on DESC, so map them below every real total.
                    if (weeklyMode) {
                        listOf(cb.desc(cb.coalesce(exactTotalExpr, cb.literal(BigDecimal.ONE.negate()))))
                    } else {
                        lengthGroup + cb.desc(cb.coalesce(totalPriceExpr as Expression<BigDecimal>, cb.literal(BigDecimal.ONE.negate())))
                    }
                }

                "lowestPrepayment" -> {
                    listOf(cb.asc(cb.min(root.get<BigDecimal>("lowestPrepayment"))))
                }

                "discount" -> {
                    // Deals/promo pages: biggest saving vs partner list price first.
                    // Ordered by the client/list ratio of the SAME searched-week
                    // expressions the card displays, so the ordering matches the
                    // "-X%" chips. NULLIF guards zero list prices; COALESCE sends
                    // yachts without a list price (ratio NULL) to the end together
                    // with undiscounted ones (ratio 1); ties break cheapest-first.
                    val savingRatio =
                        cb.coalesce(
                            cb.quot(
                                periodPrice(root.get<BigDecimal>("clientPrice")),
                                cb.nullif(
                                    periodPrice(root.get<BigDecimal>("listPrice")),
                                    BigDecimal.ZERO,
                                ),
                            ).`as`(BigDecimal::class.java),
                            cb.literal(BigDecimal.ONE),
                        )
                    lengthGroup + listOf(cb.asc(savingRatio), cb.asc(totalPriceExpr))
                }

                "lengthAsc" -> {
                    // Yachts without length land at the end — COALESCE maps NULL to a
                    // large value so ascending order pushes them to the bottom. Postgres'
                    // default ASC already does this; the COALESCE makes it explicit and
                    // dialect-independent. MIN() because only `id` is grouped —
                    // length is constant per yacht, so MIN is the value itself.
                    listOf(cb.asc(cb.coalesce(cb.min(root.get<BigDecimal>("length")), cb.literal(BigDecimal.valueOf(9999)))))
                }

                "lengthDesc" -> {
                    // Default Postgres DESC puts NULLs first, which surfaces yachts
                    // with no length as the "longest" — wrong. Map NULL to -1 so they
                    // fall to the bottom while real lengths still sort largest-first.
                    listOf(cb.desc(cb.coalesce(cb.min(root.get<BigDecimal>("length")), cb.literal(BigDecimal.valueOf(-1)))))
                }

                "recommendedScore", "recommended" -> {
                    // Curated partners first (DESC on the 0/1 boost ⇒ 1s before 0s),
                    // then cheapest within each bucket. The legacy
                    // `recommended_score` column is left in the view but no longer
                    // drives this sort.
                    listOf(cb.desc(recommendedBoost)) + lengthGroup + cb.asc(totalPriceExpr)
                }

                "id" -> {
                    // Stable catalogue order for the yacht sitemap shards (audit B03): a price-ordered
                    // listing moves boats between shards as prices change, so shards cached an hour
                    // apart listed some boats twice and others never. Only the id (appended below).
                    emptyList()
                }

                "idDesc" -> {
                    // The highest id first (size=1: the upper end of the id-range shards).
                    listOf(cb.desc(root.get<Long>("id")))
                }

                else -> {
                    // Empty / unknown sortBy ⇒ same behaviour as the Recommended tab.
                    listOf(cb.desc(recommendedBoost)) + lengthGroup + cb.asc(totalPriceExpr)
                }
            }
        cq.orderBy(primaryOrders + cb.asc(root.get<Long>("id")))

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

        // Admin-only: which partner system each yacht is synced from ("MMK" /
        // "NauSys"), so the broker can tell duplicate listings of the same
        // physical yacht apart (Mario 11.7.2026). One extra query per page,
        // skipped entirely for customer searches (isAdmin false).
        val sourceSystemByYachtId = if (isAdmin) fetchYachtSourceSystems(results.map { it.id }) else emptyMap()

        // One primary-key lookup per page, outside the listing query: each boat's capacity columns (the card's
        // `capacity` block, capacity contract v1) and when its own public record last changed (V9_71 trigger) — the
        // sitemaps' <lastmod> (Codex audit N7). The internal remark is read for admins only.
        val pageFactsByYachtId = fetchPageYachtFacts(results.map { it.id }, isAdmin)

        // Bulk-fetch live option rows for the optioned yachts on this page —
        // one extra query regardless of page size. Options come from
        // `external_reservations`, populated by MMK + Nausys availability
        // sync. "Live" includes rows whose nominal expiry lapsed less than
        // OPTION_ECHO_GRACE_HOURS ago and rows without an expiry at all
        // (partners hold past the stated deadline without bumping it —
        // Vernicos 28.7.2026). Two derived views: the id set gates isOption
        // below; the expiry map (soonest deadline per yacht — more than one
        // overlapping option row is possible: yacht swap mid-option, partner
        // quirk) feeds the broker "Option expires" stamp. Skipped entirely
        // when no yachts on the page are optioned — keeps the query cheap
        // for the common case.
        val optionedIds = results
            .filter { it.offerStatus == 2 || it.offerStatus == 3 }
            .map { it.id }
        val searchStart = searchParams.startDate
        val searchEnd = searchParams.endDate
        val liveOptionRows =
            if (optionedIds.isNotEmpty() && searchStart != null && searchEnd != null) {
                externalReservationRepository.findOptionsByYachtIdsAndPeriod(
                    optionedIds,
                    hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus.OPTION,
                    searchStart,
                    searchEnd,
                    java.time.LocalDateTime.now().minusHours(
                        hr.workspace.boat4you.domains.catalouge.enums.OPTION_ECHO_GRACE_HOURS,
                    ),
                )
            } else {
                emptyList()
            }
        val optionBackedYachtIds: Set<Long> = liveOptionRows.mapNotNull { it.yacht?.id }.toSet()
        val optionExpiryByYachtId: Map<Long, java.time.LocalDateTime> =
            liveOptionRows
                .asSequence()
                .mapNotNull { r ->
                    val yachtId = r.yacht?.id ?: return@mapNotNull null
                    val expiry = r.optionExpiration ?: return@mapNotNull null
                    yachtId to expiry
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, expiries) -> expiries.min() }

        // Multi-week period price: when the searched window is fully tiled by weekly offers
        // (and no exact-period offer exists), swap the single-week per-day rate for the summed
        // period total so the card shows the true 2-week price (e.g. 7615.30, not 5212.90).
        val views =
            results.map { rawView ->
                applyCoveringPeriodPrice(
                    applyChosenOffer(rawView, datedSearch, weeklyMode),
                    requestedNights,
                    searchParams.startDate,
                    searchParams.endDate,
                )
            }

        // Admin-only: Bareboat / Skippered / Crewed of the offer each card shows (Offers workspace pill, Mario
        // 9.10.2026). Two indexed lookups per page, skipped entirely for customer searches.
        val offerChartersByYachtId = if (isAdmin) fetchOfferCharters(views) else emptyMap()

        val searchResponseDtos =
            views.map { view ->
                // isOption requires BOTH the aggregated offerStatus to flag
                // OPTION(2) / OPTION_WAITING(3) AND a live external reservation
                // ROW backing it (row presence, not expiry presence — a hold
                // without a deadline is still a hold). Partner sync can leave
                // offer.status stuck at OPTION months after the actual option
                // lapsed (the sync only writes the OPTION snapshot; nothing
                // clears it on its own). Without this gate the listing would
                // stamp a fake "Under option" badge on yachts that are
                // actually free — matching what /yacht/.../offers shows.
                val isOption =
                    (view.offerStatus == 2 || view.offerStatus == 3) &&
                        view.id in optionBackedYachtIds

                // Honest matched-window kind: compare the chosen slot's REAL
                // window (offerDateFrom/offerDateTo, from exactOrEarliest) to the
                // searched period so the card can label EXACT vs SHIFTED/SHORTER/
                // LONGER. Null when no dated search or the slot has no window.
                val matchKind = resolveMatchKind(view.offerDateFrom, view.offerDateTo, searchStart, searchEnd)

                yachtMapper.toDto(
                    view,
                    searchParams.currency,
                    language,
                    isOption,
                    amenityKeysByYachtId[view.id],
                    optionExpiryByYachtId[view.id],
                    matchKind,
                    sourceSystemByYachtId[view.id],
                    pageFactsByYachtId[view.id]?.updatedAt,
                    pageFactsByYachtId[view.id]?.capacity,
                    offerChartersByYachtId[view.id],
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
     * Admin-only: yachtId -> partner system label ("MMK" / "NauSys") for the
     * yachts on the current search page. A yacht row belongs to exactly one
     * agency, which belongs to exactly one source system, so `external_mapping`
     * (type='Yacht') has a single row per yacht id — no ambiguity. One extra
     * indexed query per page; never called for customer searches.
     */
    private fun fetchYachtSourceSystems(yachtIds: List<Long>): Map<Long, String> {
        if (yachtIds.isEmpty()) return emptyMap()

        @Suppress("UNCHECKED_CAST")
        val rows =
            entityManager
                .createNativeQuery(
                    """
                    SELECT em.system_id, em.external_system_id
                    FROM external_mapping em
                    WHERE em.type = 'Yacht' AND em.system_id IN (:yachtIds)
                    """.trimIndent(),
                ).setParameter("yachtIds", yachtIds)
                .resultList as List<Array<Any?>>

        return rows.mapNotNull { row ->
            val yachtId = (row[0] as? Number)?.toLong() ?: return@mapNotNull null
            val label =
                when ((row[1] as? Number)?.toInt()) {
                    ExternalSystemEnum.MMK.value -> "MMK"
                    ExternalSystemEnum.NAUSYS.value -> "NauSys"
                    else -> return@mapNotNull null
                }
            yachtId to label
        }.toMap()
    }

    private data class PageYachtFacts(
        val updatedAt: Instant?,
        val capacity: CapacityColumns,
    )

    /**
     * The yachts on this page by primary key: their capacity columns (capacity contract v1, 2.2) and yacht_content_modified
     * (V9_71) in whole seconds - a yacht without a modified row gets a null updatedAt. One query per page, as the
     * modified-time lookup alone was before. `internal_remark` (admin-only) is selected only for admin searches.
     */
    private fun fetchPageYachtFacts(
        yachtIds: List<Long>,
        isAdmin: Boolean,
    ): Map<Long, PageYachtFacts> {
        if (yachtIds.isEmpty()) return emptyMap()
        val remark = if (isAdmin) "y.internal_remark" else "CAST(NULL AS text)"

        @Suppress("UNCHECKED_CAST")
        val rows =
            entityManager
                .createNativeQuery(
                    """
                    SELECT y.id, y.cabins, y.berths, y.wc, y.crew_cabins, y.crew_berths, y.crew_wc, y.cabin_berths,
                           y.salon_berths, y.showers, y.crew_showers, y.max_persons, y.recommended_persons, y.crew_number,
                           y.cabins_note, y.berths_note, y.wc_note, CAST(FLOOR(EXTRACT(EPOCH FROM m.modified_at)) AS bigint),
                           $remark
                    FROM yacht y
                    LEFT JOIN yacht_content_modified m ON m.yacht_id = y.id
                    WHERE y.id IN (:yachtIds)
                    """.trimIndent(),
                ).setParameter("yachtIds", yachtIds)
                .resultList as List<Array<Any?>>

        fun short(v: Any?): Short? = (v as? Number)?.toShort()
        return rows.mapNotNull { row ->
            val yachtId = (row[0] as? Number)?.toLong() ?: return@mapNotNull null
            yachtId to
                PageYachtFacts(
                    updatedAt = (row[17] as? Number)?.toLong()?.let { Instant.ofEpochSecond(it) },
                    capacity =
                        CapacityColumns(
                            cabins = short(row[1]),
                            berths = short(row[2]),
                            wc = short(row[3]),
                            crewCabins = short(row[4]),
                            crewBerths = short(row[5]),
                            crewWc = short(row[6]),
                            cabinBerths = short(row[7]),
                            salonBerths = short(row[8]),
                            showers = short(row[9]),
                            crewShowers = short(row[10]),
                            maxPersons = short(row[11]),
                            recommendedPersons = short(row[12]),
                            crewNumber = short(row[13]),
                            cabinsNote = row[14] as String?,
                            berthsNote = row[15] as String?,
                            wcNote = row[16] as String?,
                            internalRemark = row[18] as String?,
                        ),
                )
        }.toMap()
    }

    private class CardOffer(
        val id: Long,
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
        val locationFrom: Long?,
        val locationTo: Long?,
        /** Bookable as the boat page reads it: not RESERVED / SERVICE / UNAVAILABLE (an option counts, toCustomerStatus). */
        val bookable: Boolean,
        val product: CharterType?,
        val obligatoryCharges: MutableList<String> = mutableListOf(),
    )

    private class YachtObligatoryCharge(
        val name: String,
        val validFrom: LocalDate?,
        val validTo: LocalDate?,
    ) {
        /** Same window rule as PriceCalculationService: the row's validity covers the charter's first day. */
        fun validOn(day: LocalDate?): Boolean = day == null || ((validFrom == null || !validFrom.isAfter(day)) && (validTo == null || !validTo.isBefore(day)))
    }

    /**
     * The offer of one period that the boat page - and so the Offers workspace's "Add to offer" and the client's booking -
     * takes: [pickOfferForPeriod]'s order (bookable, round trip, home base, highest id), rows in id order like the
     * repository returns them.
     */
    private fun pickCardOffer(
        offers: List<CardOffer>,
        homeBaseId: Long?,
    ): CardOffer? {
        if (offers.size <= 1) return offers.firstOrNull()
        val byId = offers.sortedBy { it.id }
        val bookable = byId.filter { it.bookable }.ifEmpty { byId }
        val candidates = bookable.filter { it.locationFrom != null && it.locationFrom == it.locationTo }.ifEmpty { bookable }
        return candidates.firstOrNull { homeBaseId != null && it.locationFrom == homeBaseId } ?: candidates.maxByOrNull { it.id }
    }

    /**
     * ADMIN ONLY (Offers workspace, Mario 9.10.2026): Bareboat / Skippered / Crewed of the offer each card stands for,
     * classified by [OfferCharterRules]. That offer is the one "Add to offer" adds for the card's window (offerDateFrom /
     * offerDateTo): the boat page's pick for that period ([pickCardOffer]); a multi-week card (tiled weeks) reads its
     * first week; no offer (custom boat) reads the boat alone. Two indexed lookups per page (the yachts' offers in the
     * page's date range with their obligatory rows; the yachts' home base, charter types and obligatory rows), never for
     * customer searches.
     */
    private fun fetchOfferCharters(views: List<YachtSearchSelectResult>): Map<Long, OfferCharterDto> {
        if (views.isEmpty()) return emptyMap()
        val yachtIds = views.map { it.id }
        val fromMin = views.mapNotNull { it.offerDateFrom }.minOrNull()
        val toMax = views.mapNotNull { it.offerDateTo }.maxOrNull()

        fun day(v: Any?): LocalDate? = (v as? LocalDate) ?: (v as? java.sql.Date)?.toLocalDate()

        fun id(v: Any?): Long? = (v as? Number)?.toLong()

        fun charterType(v: Any?): CharterType? = (v as? String)?.let { name -> CharterType.entries.firstOrNull { it.name == name } }

        val blocked = setOf(OfferStatus.RESERVED.name, OfferStatus.SERVICE.name, OfferStatus.UNAVAILABLE.name)

        val offersByYacht = mutableMapOf<Long, MutableMap<Long, CardOffer>>()
        if (fromMin != null && toMax != null) {
            @Suppress("UNCHECKED_CAST")
            val rows =
                entityManager
                    .createNativeQuery(
                        """
                        SELECT o.id, o.yacht_id, o.date_from, o.date_to, o.location_from, o.location_to, o.status, o.product, oe.name
                        FROM offer o
                        LEFT JOIN offer_extras oe ON oe.offer_id = o.id AND oe.obligatory = true
                        WHERE o.yacht_id IN (:yachtIds) AND o.date_from >= :fromMin AND o.date_to <= :toMax
                        """.trimIndent(),
                    ).setParameter("yachtIds", yachtIds)
                    .setParameter("fromMin", fromMin)
                    .setParameter("toMax", toMax)
                    .resultList as List<Array<Any?>>
            rows.forEach { row ->
                val offerId = id(row[0]) ?: return@forEach
                val yachtId = id(row[1]) ?: return@forEach
                val from = day(row[2]) ?: return@forEach
                val to = day(row[3]) ?: return@forEach
                val offer =
                    offersByYacht.getOrPut(yachtId) { mutableMapOf() }.getOrPut(offerId) {
                        CardOffer(offerId, from, to, id(row[4]), id(row[5]), row[6]?.toString() !in blocked, charterType(row[7]))
                    }
                (row[8] as? String)?.takeIf { it.isNotBlank() }?.let { offer.obligatoryCharges += it }
            }
        }

        // 'H' home base, 'T' partner charter types, 'X' obligatory yacht-level charges
        @Suppress("UNCHECKED_CAST")
        val yachtRows =
            entityManager
                .createNativeQuery(
                    """
                    SELECT 'H', y.id, CAST(y.location_id AS text), CAST(NULL AS date), CAST(NULL AS date)
                    FROM yacht y WHERE y.id IN (:yachtIds)
                    UNION ALL
                    SELECT 'T', yct.yacht_id, yct.type, CAST(NULL AS date), CAST(NULL AS date)
                    FROM yacht_charter_type yct WHERE yct.yacht_id IN (:yachtIds)
                    UNION ALL
                    SELECT 'X', ye.yacht_id, ye.name, ye.valid_from, ye.valid_to
                    FROM yacht_extras ye WHERE ye.yacht_id IN (:yachtIds) AND ye.obligatory = true
                    """.trimIndent(),
                ).setParameter("yachtIds", yachtIds)
                .resultList as List<Array<Any?>>
        val homeBaseByYacht = mutableMapOf<Long, Long>()
        val typesByYacht = mutableMapOf<Long, MutableSet<CharterType>>()
        val chargesByYacht = mutableMapOf<Long, MutableList<YachtObligatoryCharge>>()
        yachtRows.forEach { row ->
            val yachtId = id(row[1]) ?: return@forEach
            val value = (row[2] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
            when (row[0]?.toString()) {
                "H" -> value.toLongOrNull()?.let { homeBaseByYacht[yachtId] = it }
                "T" -> charterType(value)?.let { typesByYacht.getOrPut(yachtId) { mutableSetOf() } += it }
                "X" -> chargesByYacht.getOrPut(yachtId) { mutableListOf() } += YachtObligatoryCharge(value, day(row[3]), day(row[4]))
            }
        }

        return views.associate { view ->
            val from = view.offerDateFrom
            val to = view.offerDateTo
            val offers = offersByYacht[view.id]?.values.orEmpty()
            val offer =
                if (from == null || to == null) {
                    null
                } else {
                    val period =
                        offers.filter { it.dateFrom == from && it.dateTo == to }.ifEmpty {
                            // a multi-week card: the weeks inside its window, the first one speaks
                            val inside = offers.filter { !it.dateFrom.isBefore(from) && !it.dateTo.isAfter(to) }
                            inside.minOfOrNull { it.dateFrom }?.let { first -> inside.filter { it.dateFrom == first } }.orEmpty()
                        }
                    pickCardOffer(period, homeBaseByYacht[view.id])
                }
            val firstDay = offer?.dateFrom ?: from
            val charges =
                offer?.obligatoryCharges.orEmpty() +
                    chargesByYacht[view.id].orEmpty().filter { it.validOn(firstDay) }.map { it.name }
            view.id to
                OfferCharterRules.classify(
                    gulet = GuletRules.isGulet(view.vesselType, view.modelName),
                    product = offer?.product,
                    yachtTypes = typesByYacht[view.id].orEmpty(),
                    obligatoryCharges = charges,
                )
        }
    }

    /**
     * Classify the matched slot's REAL window vs the searched period (Deploy 4
     * step 5). Returns null when there's no dated search or the slot has no
     * window (custom yacht). EXACT only when both endpoints align; otherwise
     * SHIFTED (same duration), SHORTER, or LONGER by duration comparison.
     */
    private fun resolveMatchKind(
        offerFrom: LocalDate?,
        offerTo: LocalDate?,
        searchStart: LocalDate?,
        searchEnd: LocalDate?,
    ): MatchKind? {
        if (offerFrom == null || offerTo == null || searchStart == null || searchEnd == null) return null
        if (offerFrom == searchStart && offerTo == searchEnd) return MatchKind.EXACT
        val offerDays = java.time.temporal.ChronoUnit.DAYS.between(offerFrom, offerTo)
        val searchDays = java.time.temporal.ChronoUnit.DAYS.between(searchStart, searchEnd)
        return when {
            offerDays < searchDays -> MatchKind.SHORTER
            offerDays > searchDays -> MatchKind.LONGER
            else -> MatchKind.SHIFTED
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
        client: Expression<BigDecimal>,
        dateFrom: Expression<LocalDate>,
        dateTo: Expression<LocalDate>,
        start: LocalDate?,
        end: LocalDate?,
    ): Expression<BigDecimal> {
        // A 0 € offer row is partner sync noise, never a bookable price (Sun Odyssey 479 "Sirius",
        // 25.9.2026: a FREE week at 0 € put "7 days 0 €" on top of the Greece listing). The
        // fallback MIN therefore prefers the positive rows and only reads 0 when nothing else is
        // there. The exact searched-period row stays as it is: swapping in another week's price
        // for the searched week would be worse than no price (the web shows "price on request").
        //
        // 26.9.2026: and never reads 0 at all - a yacht whose every matching row is 0 EUR has no price (NULL: the web
        // says "price on request"), not "0 EUR". Dated searches override this column with the chosen offer
        // (offerChoiceKey), so this only prices the undated default path (sister sites, admin, AI chat).
        // Nor a placeholder: only rows whose CLIENT price reaches the floor ([MIN_WEEK_PRICE_PER_DAY], 300 EUR a week)
        // count, for all three price columns, so the list price and commission come from the same trusted rows.
        val trusted = cb.greaterThanOrEqualTo(client, MIN_WEEK_PRICE_PER_DAY)
        val positiveOnly =
            cb.selectCase<BigDecimal>()
                .`when`(cb.and(trusted, cb.greaterThan(value, BigDecimal.ZERO)), value)
                .otherwise(cb.nullLiteral(BigDecimal::class.java))
        val minAll = cb.min(positiveOnly)
        if (start == null || end == null) return minAll
        val exactOnly =
            cb.selectCase<BigDecimal>()
                .`when`(cb.and(cb.equal(dateFrom, start), cb.equal(dateTo, end), trusted), value)
                .otherwise(cb.nullLiteral(BigDecimal::class.java))
        return cb.coalesce(cb.min(exactOnly), minAll)
    }

    /**
     * One row of the undated weekly "from" price: a 7-night offer of at least 300 EUR (below that it
     * is a partner placeholder) that starts today or later and is not firmly sold (RESERVED /
     * SERVICE) — an option still counts,
     * the card badges it. Custom (admin-managed) yachts have no offer dates and carry their
     * weekly low price as a 7-day row, so they always qualify.
     */
    private fun weeklyCandidate(
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
    ): Predicate {
        val dateFrom = root.get<LocalDate>("dateFrom")
        return cb.and(
            cb.equal(root.get<Int>("numberOfDays"), WEEK_NIGHTS),
            cb.greaterThanOrEqualTo(root.get<BigDecimal>("clientPrice"), MIN_WEEK_PRICE_PER_DAY),
            cb.or(cb.isNull(dateFrom), cb.greaterThanOrEqualTo(dateFrom, (cb as HibernateCriteriaBuilder).localDate())),
            cb.not(root.get<OfferStatus>("offerStatus").`in`(OfferStatus.RESERVED, OfferStatus.SERVICE)),
        )
    }

    /**
     * Aggregate [value] over the yacht's weekly candidate rows ([weeklyCandidate]): MIN of the
     * per-day price columns (× 7 = the week price the card shows) or of the day count (7). NULL
     * when the yacht has no candidate week, or when its cheapest week is an outlier below
     * [WEEKLY_PRICE_OUTLIER_RATIO] of its dearest one (a partner typo) — the card then reads
     * "price on request" and the price sorts put it last. The list price / commission are the
     * MIN over the same rows, so the crossed-out price never exceeds the list price of the
     * cheapest week (a shown discount can only understate, never overstate).
     */
    private fun <T : Number> weeklyFromValue(
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
        value: Expression<T>,
        type: Class<T>,
    ): Expression<T> {
        val weeklyClient =
            cb.selectCase<BigDecimal>()
                .`when`(weeklyCandidate(cb, root), root.get<BigDecimal>("clientPrice"))
                .otherwise(cb.nullLiteral(BigDecimal::class.java))
        val weeklyClientMax =
            cb.selectCase<BigDecimal>()
                .`when`(weeklyCandidate(cb, root), root.get<BigDecimal>("clientPrice"))
                .otherwise(cb.nullLiteral(BigDecimal::class.java))
        val trusted =
            cb.greaterThanOrEqualTo(cb.min(weeklyClient), cb.prod(cb.max(weeklyClientMax), WEEKLY_PRICE_OUTLIER_RATIO))
        val weeklyValue =
            cb.selectCase<T>()
                .`when`(weeklyCandidate(cb, root), value)
                .otherwise(cb.nullLiteral(type))
        return cb.selectCase<T>()
            .`when`(trusted, cb.min(weeklyValue))
            .otherwise(cb.nullLiteral(type))
    }

    /**
     * Dated searches (26.9.2026 audit B15): the card priced one offer and linked another. Price, day count and the two
     * offer dates were separate aggregates, so a 3-night search (12-15 June) showed "Price for 7 days" from one row and
     * linked startDate=endDate=12 June (the dateTo of the week BEFORE) - "not available for your selected dates".
     *
     * This key makes MIN() over a yacht's rows pick ONE offer and carry all of its figures: tier (see [CHOICE_EXACT]),
     * distance of its start from the searched start in days, its dates, its client total, then list and commission
     * totals. Lexicographic order = the preference order; [applyChosenOffer] decodes the winner.
     */
    private fun offerChoiceKey(
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
        start: LocalDate,
        end: LocalDate,
        nights: Int,
    ): Expression<String> {
        val dateFrom = root.get<LocalDate>("dateFrom")
        val dateTo = root.get<LocalDate>("dateTo")
        val days = root.get<Int>("numberOfDays")
        val client = root.get<BigDecimal>("clientPrice")
        val tier =
            cb.selectCase<String>()
                // no price, 0 EUR or a placeholder below the floor (a "10 EUR" week): never the card's price
                .`when`(cb.or(cb.isNull(client), cb.lessThan(client, MIN_WEEK_PRICE_PER_DAY)), CHOICE_NO_PRICE)
                // custom (admin-managed) yachts have no offer dates: their weekly low price, any week
                .`when`(cb.isNull(dateFrom), CHOICE_SAME_LENGTH)
                .`when`(cb.and(cb.equal(dateFrom, start), cb.equal(dateTo, end)), CHOICE_EXACT)
                .`when`(cb.equal(days, nights), CHOICE_SAME_LENGTH)
                .`when`(cb.and(cb.lessThanOrEqualTo(dateFrom, start), cb.greaterThanOrEqualTo(dateTo, end)), CHOICE_COVERS)
                .otherwise(CHOICE_OTHER)
        // abs(date_from - start) in days, 4 digits: Postgres' date_mi(date, date) is the `-` operator on dates;
        // a custom yacht (no dates) reads 0000
        val distance =
            cb.coalesce(
                cb.function(
                    "to_char",
                    String::class.java,
                    cb.function("abs", Int::class.javaObjectType, cb.function("date_mi", Int::class.javaObjectType, dateFrom, cb.literal(start))),
                    cb.literal(KEY_DISTANCE_FORMAT),
                ),
                "0".repeat(KEY_DISTANCE_WIDTH),
            )
        return concat(
            cb,
            tier,
            distance,
            dayKey(cb, dateFrom),
            dayKey(cb, dateTo),
            totalKey(cb, client, days),
            cb.literal("|"),
            cb.coalesce(totalKey(cb, root.get("listPrice"), days), ""),
            cb.literal("|"),
            cb.coalesce(totalKey(cb, root.get("brokerCommission"), days), ""),
        )
    }

    /**
     * Undated priceBasis=week: the dates of the cheapest weekly candidate ([weeklyCandidate]) — the week whose price the
     * card shows (before, the card carried the earliest offer's dates, possibly a past week). Key = client total(15),
     * date from(8), date to(8): MIN() = the cheapest week, the earliest on a tie. NULL for other rows.
     */
    private fun weeklyWeekKey(
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
    ): Expression<String> =
        cb.selectCase<String>()
            .`when`(
                weeklyCandidate(cb, root),
                concat(cb, totalKey(cb, root.get("clientPrice"), root.get("numberOfDays")), dayKey(cb, root.get("dateFrom")), dayKey(cb, root.get("dateTo"))),
            ).otherwise(cb.nullLiteral(String::class.java))

    /** yyyyMMdd, or [NO_DATE] for a custom yacht (no offer dates). */
    private fun dayKey(
        cb: CriteriaBuilder,
        date: Expression<LocalDate>,
    ): Expression<String> = cb.coalesce(cb.function("to_char", String::class.java, date, cb.literal("YYYYMMDD")), NO_DATE)

    /** Per-day price × days as a fixed-width string (lexicographic = numeric order for non-negative totals). */
    private fun totalKey(
        cb: CriteriaBuilder,
        perDay: Expression<BigDecimal>,
        days: Expression<Int>,
    ): Expression<String> =
        cb.function("to_char", String::class.java, cb.prod(perDay, cb.toBigDecimal(days)), cb.literal(KEY_TOTAL_FORMAT))

    private fun concat(
        cb: CriteriaBuilder,
        vararg parts: Expression<String>,
    ): Expression<String> = parts.reduce { acc, part -> cb.concat(acc, part) }

    /** The chosen offer's client total (numeric), for the price sorts — the same figure the card shows. */
    private fun chosenOfferTotal(
        cb: CriteriaBuilder,
        key: Expression<String>,
    ): Expression<BigDecimal> {
        val total =
            cb.function("to_number", BigDecimal::class.java, cb.substring(key, KEY_TOTAL_START, KEY_TOTAL_WIDTH), cb.literal(KEY_TOTAL_PARSE))
        return cb.selectCase<BigDecimal>()
            .`when`(cb.equal(cb.substring(key, 1, 1), CHOICE_NO_PRICE), cb.nullLiteral(BigDecimal::class.java))
            .otherwise(total)
    }

    /** 0 = the searched length (exact / shifted), 1 = a longer or other offer, 2 = no price. */
    private fun choiceGroup(
        cb: CriteriaBuilder,
        key: Expression<String>,
    ): Expression<Int> {
        val tier = cb.substring(key, 1, 1)
        return cb.selectCase<Int>()
            .`when`(tier.`in`(CHOICE_EXACT, CHOICE_SAME_LENGTH), 0)
            .`when`(tier.`in`(CHOICE_COVERS, CHOICE_OTHER), 1)
            .otherwise(2)
    }

    /**
     * Replace the aggregate price / days / dates with the chosen offer's own (see [offerChoiceKey], [weeklyWeekKey]).
     * Dated: all four come from the one chosen row; a yacht whose only rows have no positive price gets no price.
     * Weekly mode: only the dates (the price columns already are that week's); a yacht without a trusted week (price
     * NULL) carries no dates, so no card links to a week nobody can book.
     */
    private fun applyChosenOffer(
        view: YachtSearchSelectResult,
        dated: Boolean,
        weekly: Boolean,
    ): YachtSearchSelectResult {
        if (!dated && !weekly) return view
        val key = view.chosenOfferKey
        if (weekly) {
            return if (key == null || view.clientPrice == null) {
                view.copy(offerDateFrom = null, offerDateTo = null)
            } else {
                view.copy(offerDateFrom = keyDate(key, KEY_TOTAL_WIDTH), offerDateTo = keyDate(key, KEY_TOTAL_WIDTH + 8))
            }
        }
        // a custom yacht keeps its weekly low price, without dates
        if (key == null || key.substring(5, 13) == NO_DATE) return view
        if (key.startsWith(CHOICE_NO_PRICE)) {
            // "price on request" for the searched period: no offer dates, so a card link uses the searched pair
            return view.copy(
                clientPrice = null,
                listPrice = null,
                brokerCommission = null,
                numberOfDays = null,
                offerDateFrom = null,
                offerDateTo = null,
            )
        }
        val from = keyDate(key, 5)!!
        val to = keyDate(key, 13)!!
        val nights = java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt().coerceAtLeast(1)
        val totals = key.substring(KEY_TOTAL_START - 1).split('|')
        fun perDay(total: String?): BigDecimal? =
            total?.takeIf { it.isNotBlank() }?.toBigDecimal()?.divide(BigDecimal(nights), 10, java.math.RoundingMode.HALF_UP)
        return view.copy(
            clientPrice = perDay(totals.getOrNull(0)),
            listPrice = perDay(totals.getOrNull(1)),
            brokerCommission = perDay(totals.getOrNull(2)),
            numberOfDays = nights,
            offerDateFrom = from,
            offerDateTo = to,
        )
    }

    /** yyyyMMdd at the 0-based [index] of a choice key; null for [NO_DATE]. */
    private fun keyDate(
        key: String,
        index: Int,
    ): LocalDate? =
        key.substring(index, index + 8).takeIf { it != NO_DATE }?.let { LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }

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
     * Multi-week period pricing (2026-06-29). Charter weeks are sold Sat→Sat (7 nights), so a
     * 2-week request like 15–29 May has NO single offer row whose dates are exactly the period —
     * only weekly rows (15–22, 22–29). The [exactPeriodOrMin] path then falls back to MIN(per-day)
     * and pairs it with one week's day-count, showing one (often a cheaper neighbouring) week's
     * price for the whole period. Instead, SUM the period totals (per-day × days) of the offers
     * that fall FULLY INSIDE [start,end] — these tile the window — to get the true period price.
     *
     * `dateFrom >= start AND dateTo <= end` (fully-inside, NOT the NEARBY-padded slot predicate) so
     * an adjacent week ending on the check-in day (e.g. 8–15 for a 15–29 search) is excluded.
     * Caller only trusts the sum when the covered nights equal the requested nights (full tiling)
     * and no exact single-period offer already exists (avoids double-counting a 14-night row plus
     * its component weeks).
     */
    private fun coveringPeriodTotal(
        cb: CriteriaBuilder,
        perDay: Expression<BigDecimal>,
        days: Expression<Int>,
        dateFrom: Expression<LocalDate>,
        dateTo: Expression<LocalDate>,
        start: LocalDate?,
        end: LocalDate?,
        client: Expression<BigDecimal>,
    ): Expression<BigDecimal>? {
        if (start == null || end == null) return null
        val inRange = coveringWeekPredicate(cb, days, dateFrom, dateTo, start, end, client)
        val perOfferTotal = cb.prod(perDay, cb.toBigDecimal(days))
        return cb.sum(
            cb.selectCase<BigDecimal>().`when`(inRange, perOfferTotal).otherwise(cb.literal(BigDecimal.ZERO)),
        )
    }

    private fun coveringPeriodNights(
        cb: CriteriaBuilder,
        days: Expression<Int>,
        dateFrom: Expression<LocalDate>,
        dateTo: Expression<LocalDate>,
        start: LocalDate?,
        end: LocalDate?,
        client: Expression<BigDecimal>,
    ): Expression<Int>? {
        if (start == null || end == null) return null
        val inRange = coveringWeekPredicate(cb, days, dateFrom, dateTo, start, end, client)
        return cb.sum(
            cb.selectCase<Int>().`when`(inRange, days).otherwise(cb.literal(0)),
        )
    }

    /**
     * WEEKLY offers fully inside [start,end]. The duration restriction is what makes
     * `coveringNights == requested` a reliable full-tiling test: partners also materialise
     * overlapping 14/21-night rows for the same window (KARINA Elba 45, 23.8.2026 — a 28-night
     * search summed 28+21+14+14+14 = 91 nights, the check failed and the card fell back to the
     * cheapest week's per-day rate x 28, quoting a total no partner honours). Weeks are the
     * tiling unit; an exact single-period offer still wins via the numberOfDays check in
     * applyCoveringPeriodPrice. A placeholder week below [MIN_WEEK_PRICE_PER_DAY] is not a tile, so a period
     * of "10 EUR" weeks never sums to a price (26.9.2026 review: 2 x 10 EUR = "21 EUR for 14 days").
     */
    private fun coveringWeekPredicate(
        cb: CriteriaBuilder,
        days: Expression<Int>,
        dateFrom: Expression<LocalDate>,
        dateTo: Expression<LocalDate>,
        start: LocalDate,
        end: LocalDate,
        client: Expression<BigDecimal>,
    ) = cb.and(
        cb.greaterThanOrEqualTo(dateFrom, start),
        cb.lessThanOrEqualTo(dateTo, end),
        cb.equal(days, WEEK_NIGHTS),
        cb.greaterThanOrEqualTo(client, MIN_WEEK_PRICE_PER_DAY),
    )

    /**
     * Replace a search row's single-week per-day price with the multi-week period price when the
     * requested window is fully tiled by the weekly offers inside it (coveringNights == requested)
     * AND no exact single-period offer already supplies it (numberOfDays != requested — else the
     * exact row, possibly a partner-materialised 14-night offer, already holds the correct value
     * and summing would double-count). The per-day rate is kept as the DTO contract (the card
     * renders per-day × numberOfDays), so per-day = coveringTotal / requestedNights and
     * numberOfDays = requestedNights → card total = coveringTotal exactly. No-op for dateless or
     * single-week searches, custom yachts (NULL offer dates → coveringNights = 0), and partial
     * coverage (some weeks booked → coveringNights < requested → fall back to existing price).
     */
    private fun applyCoveringPeriodPrice(
        view: YachtSearchSelectResult,
        requestedNights: Int?,
        start: LocalDate?,
        end: LocalDate?,
    ): YachtSearchSelectResult {
        if (requestedNights == null || requestedNights <= WEEK_NIGHTS) return view
        val coveringNights = view.coveringNights ?: return view
        val coveringTotal = view.coveringClientTotal ?: return view
        if (coveringNights != requestedNights || view.numberOfDays == requestedNights) return view
        val nights = requestedNights.toBigDecimal()
        fun perDay(total: BigDecimal?): BigDecimal? = total?.divide(nights, 10, java.math.RoundingMode.HALF_UP)
        return view.copy(
            clientPrice = perDay(coveringTotal),
            listPrice = perDay(view.coveringListTotal),
            brokerCommission = perDay(view.coveringCommissionTotal),
            numberOfDays = requestedNights,
            // the tiled weeks ARE the searched period: the card links exactly those dates
            offerDateFrom = start ?: view.offerDateFrom,
            offerDateTo = end ?: view.offerDateTo,
        )
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
        // Blanks included, same rule as [resolveSearchDidScope].
        val didTokens = searchParams.locationIds.orEmpty()
        val locationIds =
            didTokens
                .flatMap { getMarinas(it).mapNotNull { m -> m.id } }
                .distinct()
        val pageSize = size.coerceAtMost(MAX_PAGE_SIZE)
        // Same rule as `buildYachtSearchPredicates` (16.9.2026 cusma2 load incident): a
        // destination the caller DID ask for but that resolves to nothing must restrict to
        // nothing. `locationIdsEmpty` below means "no destination filter at all", so without
        // this guard an unknown/malformed `did` would hand the admin the WHOLE catalogue.
        if (didTokens.isNotEmpty() && locationIds.isEmpty()) {
            warnUnresolvableDid(didTokens)
            return PageImpl(emptyList(), org.springframework.data.domain.PageRequest.of(page, pageSize), 0)
        }
        val agencyIds = searchParams.agencyIds.orEmpty()
        val vesselTypeValues = searchParams.vesselTypes?.map { it.name }.orEmpty()
        val startDate = searchParams.startDate ?: LocalDate.now()
        val endDate = searchParams.endDate ?: startDate.plusDays(7)
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
        val capacityColumns =
            CapacityColumns(
                cabins = row.cabins,
                crewCabins = row.crewCabins,
                wc = row.wc,
                crewWc = row.crewWc,
                berths = row.berths,
                crewBerths = row.crewBerths,
                cabinBerths = row.cabinBerths,
                salonBerths = row.salonBerths,
                showers = row.showers,
                crewShowers = row.crewShowers,
                maxPersons = row.maxPersons,
                recommendedPersons = row.recommendedPersons,
                crewNumber = row.crewNumber,
                cabinsNote = row.cabinsNote,
                berthsNote = row.berthsNote,
                wcNote = row.wcNote,
                internalRemark = row.internalRemark,
            )
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
            berths = row.berths,
            wc = row.wc,
            length = row.length,
            offerStatus = offerStatus,
            isOption = false,
            clientPriceEur = row.avgClientPrice,
            clientPriceInfo = priceInfo,
            modelName = row.modelName,
            mainImageId = row.mainImageId,
            agencyName = row.agencyName,
            capacity = yachtMapper.capacityBrief(capacityColumns),
            brokerNotes = yachtMapper.brokerNotes(capacityColumns),
        )
    }

    fun getYachtSearchTotalCount(searchParams: YachtSearchParamObject): Long {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(Long::class.java)
        val root = cq.from(YachtSearchView::class.java)

        // SELECT DISTINCT id + count the (small) id list in Kotlin instead of
        // COUNT(DISTINCT <entity>): the DISTINCT-aggregate form forces Postgres
        // into a sort-based dedup of every matched view row (~1.3s for one
        // country), while plain SELECT DISTINCT hash-aggregates the same rows
        // in ~0.5s. The list is one Long per yacht (a few thousand), so the
        // transfer is negligible. (9.7.2026)
        cq.select(root.get<Long>("id")).distinct(true)

        val predicates =
            buildYachtSearchPredicates(
                cq,
                cb,
                root,
                searchParams,
            )

        cq.where(*predicates.toTypedArray())
        return entityManager.createQuery(cq).resultList.size.toLong()
    }

    /** The row is a gulet: the criteria twin of [GuletRules.SQL_IS_GULET] (never NULL, so NOT / OR stay two-valued). */
    private fun isGuletRow(
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
    ): Predicate {
        val vesselType = root.get<VesselType>("vesselType")
        return cb.or(
            cb.and(cb.isNotNull(vesselType), cb.equal(vesselType, VesselType.GULET)),
            cb.like(cb.lower(cb.coalesce(root.get<String>("modelName"), "")), "%${GuletRules.MODEL_WORD}%"),
        )
    }

    /** A gulet's BAREBOAT row - the one row type a gulet never shows or matches (GuletRules). */
    private fun isGuletBareboatRow(
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
    ): Predicate = cb.and(cb.equal(root.get<CharterType>("charterType"), CharterType.BAREBOAT), isGuletRow(cb, root))

    private fun buildYachtSearchPredicates(
        cq: CriteriaQuery<*>,
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
        searchParams: YachtSearchParamObject,
    ): List<Predicate> {
        val predicates = mutableListOf<Predicate>()

        // Resolve `did` (country / region / marina) into predicates. Region
        // (`r-…`) and marina (`l-…`) ids expand into marina-id lists matched
        // against location_from/location_to — branch 1 yachts carry
        // offer.location_from at marina granularity, and branch 2 (custom)
        // also points to a marina via the admin marina selector. Country ids
        // (`c-…`) used to expand into the same 200+ marina IN-list, which the
        // planner could not serve from the location indexes (OR across two
        // huge IN-lists) — every country page walked all 1.6M view rows. They
        // now filter on the dedicated indexed country_code/country_code_to
        // columns instead; semantics unchanged (pickup OR drop-off in scope).
        //
        // Earlier we tried adding the parent country/region realId alongside,
        // to surface custom yachts pinned at country level, but Country.id and
        // Location.id share the same BIGSERIAL space — adding `8` for `r-8`
        // accidentally matched Location.id=8 (ACI Marina Trogir) and pulled
        // unrelated Croatian yachts into Greek region searches. The marina
        // selector closes that gap at the data layer instead.
        //
        // PICKUP ONLY (26.9.2026 audit B14): a destination lists the boats that START there. Matching the drop-off
        // too put boats based elsewhere on every landing that is a one-way end point — the Dubrovnik catamaran
        // landing listed Kaštela boats with a one-way week into Dubrovnik ("Catamaran charter in ACI Marina
        // Dubrovnik", 0 of 18 cards based in Dubrovnik).
        //
        // BASED HERE on an undated search (the landings, the sitemap, the gate counts; review 26.9.2026): a pickup
        // is not enough either. The Dubrovnik landings' headline cards were "ACI Marina Dubrovnik » Marina Kaštela":
        // Kaštela boats whose one-way week STARTS in Dubrovnik (repositioning weeks back to their base). An undated
        // row counts only when the boat also ends the charter in the destination (no
        // drop-off, the same place, or another place in scope) or is based there (yacht.location_id in scope). A
        // dated search keeps every pickup: for the searched dates a one-way is a real option. The facets, the relax
        // suggestions (DestinationScopeSql) and the charter facts apply the same rule, so the H2, the type chips and
        // the facts tile count the same boats.
        val did = resolveSearchDidScope(searchParams.locationIds)
        val didPredicates = mutableListOf<Predicate>()
        if (did.marinaIds.isNotEmpty()) {
            didPredicates.add(root.get<Long>("locationFrom").`in`(did.marinaIds))
        }
        if (did.countryCodes.isNotEmpty()) {
            didPredicates.add(root.get<String>("countryCode").`in`(did.countryCodes))
        }
        val undatedSearch = searchParams.startDate == null && searchParams.endDate == null
        when {
            didPredicates.isNotEmpty() && undatedSearch ->
                predicates.add(cb.and(cb.or(*didPredicates.toTypedArray()), basedHere(cq, cb, root, did)))
            didPredicates.isNotEmpty() -> predicates.add(cb.or(*didPredicates.toTypedArray()))
            // An asked-for destination that resolves to nothing restricts to nothing — see
            // [resolveSearchDidScope] (16.9.2026 cusma2 load incident). `cb.disjunction()` is
            // Hibernate's always-false `1 <> 1`, not an empty junction, so it really is emitted.
            did.matchesNothing -> predicates.add(cb.disjunction())
        }

        if (!searchParams.yachtIds.isNullOrEmpty()) {
            predicates.add(
                root.get<Long>("id").`in`(searchParams.yachtIds),
            )
        }
        searchParams.idFrom?.let { predicates.add(cb.greaterThanOrEqualTo(root.get("id"), it)) }
        searchParams.idTo?.let { predicates.add(cb.lessThan(root.get("id"), it)) }

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
            // A gulet is never bareboat (GuletRules.charterTypeSql, the native twin for the facets / relax counts):
            // BAREBOAT never matches a gulet, CREWED matches every gulet, whatever the partner tagged.
            val asked = cb.and(root.get<CharterType>("charterType").`in`(searchParams.charterTypes), cb.not(isGuletBareboatRow(cb, root)))
            predicates.add(if (CharterType.CREWED in searchParams.charterTypes) cb.or(asked, isGuletRow(cb, root)) else asked)
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
        // surface Taranto/Brindisi boats. Filters on the dedicated indexed country_code
        // column (pickup side, matching the old right(location_full_name, 2) semantics);
        // null-country regions contribute nothing so the filter quietly no-ops.
        val effectiveCountryCodes =
            searchParams.countryCodes?.takeIf { it.isNotEmpty() }
                ?: deriveRegionCountryCodes(searchParams.locationIds)
        if (effectiveCountryCodes.isNotEmpty()) {
            val codeUpper = effectiveCountryCodes.map { it.uppercase() }
            predicates.add(root.get<String>("countryCode").`in`(codeUpper))
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

        // People: the partner's max people on board, else its berths (Mario 6.10.2026, capacity contract v1 section 9):
        // max_persons is NULL on about half the fleet, which this filter used to drop. Filter / counts / AI search only
        // - never pricing (maxPersons drives per-person extras) and never shown as "max people".
        val people: Expression<Short> = cb.coalesce<Short>().value(root.get("maxPersons")).value(root.get("berths"))
        if (searchParams.minPersons != null && searchParams.maxPersons != null) {
            predicates.add(cb.between(people, searchParams.minPersons, searchParams.maxPersons))
        } else if (searchParams.minPersons != null) {
            predicates.add(cb.greaterThanOrEqualTo(people, searchParams.minPersons))
        } else if (searchParams.maxPersons != null) {
            predicates.add(cb.lessThanOrEqualTo(people, searchParams.maxPersons))
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

        // TRUE interval logic (Deploy 4, replaces the old ±DATE_FLEX_DAYS start
        // clamp). A published slot qualifies if its OWN window overlaps
        // [from - N, to + N] — judged on the slot's real dateFrom/dateTo, never
        // a start-day-only clamp. So a 10-day Tue→Fri slot that genuinely covers
        // the requested period matches, while a slot whose interval never reaches
        // it is dropped. Half-open on the slot window vs the padded request.
        //
        // On top of that, a correlated NOT EXISTS half-open overlap against
        // external_reservations drops slots whose REAL window is hard-blocked
        // (RESERVATION / SERVICE only — NEVER OPTION, so an agency optioning a
        // whole marina keeps every yacht visible, badged inquiry-only). The
        // matview offer_status UNAVAILABLE pre-filter above STAYS (HIGH-6 b):
        // owner-weeks / regatta live only on offer.status with no reservation
        // row, so the live EXISTS only ADDS blocking, it does not replace it.
        //
        // Custom yachts (entry_type=2 in yacht_search_view) carry NULL
        // dateFrom/dateTo because they have no offer rows — they're inquiry-
        // only listings that the admin maintains by hand. Without an OR
        // dateFrom IS NULL escape hatch, the date predicate would silently
        // exclude every custom yacht the moment a user picks a date, even
        // though the listing should always show ("contact us for this date").
        // They have no external_reservations rows, so the NOT EXISTS is
        // vacuously true and never drops them.
        val isCustomYacht = cb.isNull(root.get<LocalDate>("dateFrom"))
        if (searchParams.startDate != null && searchParams.endDate != null) {
            if (!searchParams.startDate.isBefore(searchParams.endDate)) {
                throw IllegalArgumentException("Starting date must be before end date")
            }
            val paddedFrom = searchParams.startDate.minusDays(NEARBY_WINDOW_DAYS)
            val paddedTo = searchParams.endDate.plusDays(NEARBY_WINDOW_DAYS)
            // Slot window [dateFrom, dateTo) overlaps padded request [paddedFrom, paddedTo).
            val slotMatches =
                cb.and(
                    cb.lessThan(root.get<LocalDate>("dateFrom"), paddedTo),
                    cb.greaterThan(root.get<LocalDate>("dateTo"), paddedFrom),
                )
            predicates.add(cb.or(isCustomYacht, slotMatches))
            predicates.add(
                cb.or(
                    isCustomYacht,
                    cb.not(
                        cb.exists(
                            buildHardBlockOverlapSubquery(cq, cb, root, searchParams.startDate, searchParams.endDate),
                        ),
                    ),
                ),
            )
        } else if (searchParams.startDate != null) {
            // Open-ended start: slot's window must reach on/after the padded start.
            predicates.add(
                cb.or(
                    isCustomYacht,
                    cb.greaterThan(root.get<LocalDate>("dateTo"), searchParams.startDate.minusDays(NEARBY_WINDOW_DAYS)),
                ),
            )
        } else if (searchParams.endDate != null) {
            // Open-ended end: slot's window must start on/before the padded end.
            predicates.add(
                cb.or(
                    isCustomYacht,
                    cb.lessThan(root.get<LocalDate>("dateFrom"), searchParams.endDate.plusDays(NEARBY_WINDOW_DAYS)),
                ),
            )
        } else {
            // Undated: one card per physical boat (audit B17) - a copy another channel already lists is skipped
            // (yacht_listing_twin, V9_69). Dated searches keep every copy: two channels can differ in availability.
            val twins = cq.subquery(Long::class.java)
            val twin = twins.from(YachtListingTwin::class.java)
            twins.select(twin.get("yachtId")).where(cb.equal(twin.get<Long>("yachtId"), root.get<Long>("id")))
            predicates.add(cb.not(cb.exists(twins)))
            // Undated (the destination landings, the sitemap, the gate counts): only offers that can still be booked,
            // i.e. starting today or later. The matview keeps offers up to 30 days after they end (RetentionReaper
            // OFFER_GRACE_DAYS), so a boat whose partner stopped publishing stayed listed - and priced by a week in the
            // past - for a month. "N boats available" now counts bookable boats, the same definition as the charter
            // facts tile (audit B12). Custom yachts have no dates and always stay.
            predicates.add(
                cb.or(
                    isCustomYacht,
                    cb.greaterThanOrEqualTo(root.get<LocalDate>("dateFrom"), (cb as HibernateCriteriaBuilder).localDate()),
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

            // Distinct CODES, not rows: a yacht with two rows of one code (two partner items both linking to it, e.g.
            // "Refrigerator" + "Fridge on flybridge") used to count 2 and drop out of a one-amenity filter.
            countSubquery
                .select(cb.countDistinct(yachtEquipmentRoot.get<Long>("equipmentId")))
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

    /**
     * Correlated subquery: does this yacht have ANY hard-block reservation
     * (status RESERVATION or SERVICE) whose REAL window overlaps the searched
     * [start, end) period? OPTION is deliberately NOT included — optioned
     * yachts stay visible (badged inquiry-only); only a firm reservation or a
     * service block hides a yacht.
     *
     * Half-open overlap (date_from < end AND date_to > start) so a turnaround
     * day is never a phantom conflict, matching V9_11 / CRIT-3.
     *
     * MED-9: the subquery is built against the CALLER's [cq] (its own
     * CriteriaQuery), so getYachts (page) and getYachtSearchTotalCount (count)
     * each construct it on their own query and the count stays consistent with
     * the visible page.
     */
    private fun buildHardBlockOverlapSubquery(
        cq: CriteriaQuery<*>,
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
        start: LocalDate,
        end: LocalDate,
    ): jakarta.persistence.criteria.Subquery<Long> {
        val sub = cq.subquery(Long::class.java)
        val er = sub.from(ExternalReservation::class.java)
        val statusPath = er.get<ExternalReservationStatus>("status")
        val optionExpirationPath = er.get<LocalDateTime>("optionExpiration")
        sub.select(cb.literal(1L)).where(
            cb.equal(er.get<Yacht>("yacht").get<Long>("id"), root.get<Long>("id")),
            statusPath.`in`(ExternalReservationStatus.RESERVATION, ExternalReservationStatus.SERVICE),
            cb.lessThan(er.get<LocalDate>("dateFrom"), end),
            cb.greaterThan(er.get<LocalDate>("dateTo"), start),
            // Option-honesty (2026-06-27): a row carrying a NON-NULL, already-past
            // optionExpiration is an expired hold the partner has freed — it must not
            // hard-block, even if mis-statused as RESERVATION ("zombie"). A genuine
            // booking has optionExpiration = NULL and keeps blocking. Mirrors the
            // partner at read time, regardless of when the purge deletes the row.
            cb.or(
                cb.isNull(optionExpirationPath),
                cb.greaterThan(optionExpirationPath, LocalDateTime.now()),
            ),
        )
        return sub
    }

    /**
     * The `did` filter resolved for the search path: marina ids for the `r-…` / `l-…` tokens,
     * 2-letter codes for the `c-…` ones. [matchesNothing] means the caller DID ask for a
     * destination but not one token of it is a real country / region / marina.
     */
    internal data class SearchDidScope(
        val marinaIds: List<Long>,
        val countryCodes: List<String>,
        val matchesNothing: Boolean,
    )

    /**
     * The undated "based here" half of a destination match (26.9.2026 audit B14, see buildYachtSearchPredicates): the
     * row's drop-off is empty or in scope, or the boat's home base (yacht.location_id) is in scope. The home-base
     * subquery is uncorrelated (a hashed sub-plan over the ~15k yachts). SQL twin: [DestinationScopeSql.basedHere].
     */
    private fun basedHere(
        cq: CriteriaQuery<*>,
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
        did: SearchDidScope,
    ): Predicate {
        val locationTo = root.get<Long>("locationTo")
        val ends = mutableListOf<Predicate>(cb.isNull(locationTo))
        val homes = cq.subquery(Long::class.java)
        val yacht = homes.from(Yacht::class.java)
        val home = mutableListOf<Predicate>()
        if (did.marinaIds.isNotEmpty()) {
            ends.add(locationTo.`in`(did.marinaIds))
            home.add(yacht.get<Location>("location").get<Long>("id").`in`(did.marinaIds))
        }
        if (did.countryCodes.isNotEmpty()) {
            ends.add(root.get<String>("countryCodeTo").`in`(did.countryCodes))
            home.add(yacht.get<Location>("location").get<String>("countryCode").`in`(did.countryCodes))
        }
        homes.select(yacht.get("id")).where(cb.or(*home.toTypedArray()))
        return cb.or(*ends.toTypedArray(), root.get<Long>("id").`in`(homes))
    }

    /**
     * Resolve `did=c-54 / r-12 / l-9001` into the ids the search can match on.
     *
     * [SearchDidScope.matchesNothing] is the fix for the 16.9.2026 cusma2 load incident: an
     * unresolvable destination used to leave the location filter OFF, which WIDENED the query
     * to the whole catalogue instead of narrowing it — `did=l-l-19` returned all 897 Croatian
     * catamarans instead of the 2 boats in marina l-19, and `did=l-9999999` returned all 13,609
     * yachts in 2.3 s. Malformed tokens are never repaired, only refused: an asked-for
     * destination that resolves to nothing must return zero rows, exactly like
     * `YachtDistributionService.resolveDidScope` already does for the facet counts.
     */
    internal fun resolveSearchDidScope(locationIds: List<String>?): SearchDidScope {
        // Blanks count as tokens: `did=,` reaches us as two blank entries, and that is a
        // destination the caller asked for which resolves to nothing — refusing it keeps the
        // 13,609-row full-catalogue scan unreachable from a junk URL, and keeps this endpoint
        // agreeing with the facet endpoints, which already answer zero rows for the same URL.
        val tokens = locationIds.orEmpty()
        val countryCodes =
            tokens
                .filter { it.firstOrNull() == 'c' }
                .mapNotNull { it.drop(2).toLongOrNull() }
                .mapNotNull { countryRepository.findById(it).orElse(null)?.code2?.uppercase() }
                .distinct()
        val marinaIds =
            tokens
                .filterNot { it.firstOrNull() == 'c' }
                .flatMap { locationId -> getMarinas(locationId).mapNotNull { it.id } }
                .distinct()
        val matchesNothing = tokens.isNotEmpty() && marinaIds.isEmpty() && countryCodes.isEmpty()
        if (matchesNothing) {
            warnUnresolvableDid(tokens)
        }
        return SearchDidScope(
            marinaIds = marinaIds,
            countryCodes = countryCodes,
            matchesNothing = matchesNothing,
        )
    }

    /**
     * The only signal that a destination went blank. Before the 16.9.2026 cusma2 load incident fix
     * a stale `did` silently WIDENED the query, so a landing page pointing at a renumbered region
     * still showed boats and nobody noticed; now it correctly shows none, which is invisible unless
     * this line exists. Throttled like the image resize gate — once a minute with the cumulative
     * count — because the refusal fires per query and crawlers arrive in bursts. Read the total as
     * queries, not requests: one `/public/yachts` call builds the predicates twice (the id count
     * and the page), so a refused search counts 2.
     */
    private fun warnUnresolvableDid(tokens: List<String>) {
        val total = didRefusals.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastDidWarnAtMs.get()
        if (now - last >= DID_REFUSAL_WARN_INTERVAL_MS && lastDidWarnAtMs.compareAndSet(last, now)) {
            val shown =
                tokens
                    .take(DID_REFUSAL_LOGGED_TOKENS)
                    .joinToString(",") { it.take(32).replace(UNSAFE_DID_CHARS, "?") }
            log.warn(
                "Unresolvable did [{}] ({} token(s)): returning zero rows instead of the whole catalogue — " +
                    "{} refused since start; next warning in >= 1 min",
                shown,
                tokens.size,
                total,
            )
        }
    }

    private fun getMarinas(locationId: String): List<Location> {
        val locationType =
            when (locationId.firstOrNull()) {
                'r' -> LocationType.REGION
                'c' -> LocationType.COUNTRY
                'l' -> LocationType.MARINA
                else -> return emptyList()
            }

        // `drop(2)`, not `substring(2)`: a one-character token (`did=l`) must resolve to
        // "unknown destination" like any other bad token, not throw out of the request.
        val id = locationId.drop(2).toIntOrNull() ?: return emptyList()

        return when (locationType) {
            // A marina can exist twice (one row per provider, spelled differently —
            // "Marina Kastela" vs "Marina Kaštela"); pull every same-place sibling so the
            // search returns BOTH fleets, not just the picked id's — but never a same-named
            // marina elsewhere, and the curated pairs no name rule finds (audit B14,
            // LocationRepository.findSamePlaceMarinaIds).
            LocationType.MARINA -> {
                val marina = locationRepository.findById(id.toLong()).orElse(null)
                when {
                    marina == null -> emptyList()
                    marina.name.isNullOrBlank() -> listOf(marina)
                    else -> {
                        // IDs first (native query can't map Location's @Formula display_name),
                        // then re-fetch via findAllById (HQL → formula-safe).
                        val ids = locationRepository.findSamePlaceMarinaIds(marina.id!!)
                        if (ids.isEmpty()) listOf(marina) else locationRepository.findAllById(ids)
                    }
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
            // `drop(2)` for the same reason as in [getMarinas]: `did=r` must not throw.
            ?.mapNotNull { it.drop(2).toLongOrNull() }
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

        // Pick-up location for a DATED request comes from that week's offers,
        // not the yacht's home base. Partners keep the master record on the
        // home marina even while the boat spends a season elsewhere (LODIRE,
        // 14.9.2026: MMK `homeBase` = Alimos/Athens, yet every Sept-Oct offer
        // departs Skiathos, ~300 km away — the detail page told the client to
        // collect the boat in Athens). The search listing was already right;
        // its view rows are per offer.
        //
        // Which offer speaks for the week: a ROUND TRIP one. Where the boat
        // physically sits is where a return charter starts and ends, while
        // one-way rows are derived options — and a base change leaves the
        // superseded route behind for ever, because the route is part of the
        // offer upsert key and nothing deletes what the partner stopped
        // sending. Row age can't break the tie either (verified 14.9: the
        // stale Skiathos>Alimos row for 24.10 outranks the valid
        // Alimos>Alimos one by id). Bookable rows are preferred, then round
        // trips; undated requests keep the home base.
        // ONE row per charter period, same rule as /standard-offers and /offers
        // (Mario 14.9.2026). The detail payload feeds the booking panel and the
        // availability strip, so leaving the duplicates here put two identical
        // 6.318 EUR cards on LODIRE's 26.9 week and let the panel select the
        // stale Alimos>Skiathos row.
        val homeBaseIdForPick = yacht.location?.id?.let { "l-$it" }
        val offerDtoForPeriods =
            offerDto
                .groupBy { it.dateFrom to it.dateTo }
                .values
                .mapNotNull { pickOfferForPeriod(it, homeBaseIdForPick) }
                .sortedBy { it.dateFrom }

        // Pick-up location for a DATED request: the offer that speaks for that
        // week decides, not the yacht's home base. Partners keep the master
        // record on the home marina even while the boat spends a season
        // elsewhere (LODIRE 14.9.2026: MMK homeBase = Alimos/Athens, yet every
        // Sept-Oct offer departs Skiathos ~300 km away). Same selection rule as
        // the detail calendar, so page header and week cards cannot disagree.
        // Undated requests keep the home base — the honest answer without a
        // period, and stable for canonical/SEO.
        val periodLocation =
            if (dateFrom != null && dateTo != null) {
                offerDtoForPeriods
                    .firstOrNull { it.locationFrom != null }
                    ?.locationFrom
                    ?.takeIf { it.id != homeBaseIdForPick }
            } else {
                null
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
                offerDtoForPeriods,
                yachtExtras,
                currency,
                language,
                periodLocation,
            )

        val withCanonical = listingCanonicalSlug(id)?.let { result.copy(listingCanonicalSlug = it) } ?: result
        return withCanonical.copy(hasBookableFutureOffer = hasBookableFutureOffer(id))
    }

    /**
     * Same test as the undated branch of [buildYachtSearchPredicates] - a search-view row starting today or later,
     * not UNAVAILABLE (CUSTOM boats have no dates and always count) - so the boat page and the listings never
     * disagree about whether the boat is offered. The view lags the offer table by one refresh (<= 10 min).
     */
    private fun hasBookableFutureOffer(id: Long): Boolean =
        entityManager
            .createNativeQuery(
                "SELECT EXISTS (SELECT 1 FROM yacht_search_view v WHERE v.id = :id " +
                    "AND (v.date_from IS NULL OR (v.date_from >= CURRENT_DATE AND v.offer_status <> 'UNAVAILABLE')))",
            ).setParameter("id", id)
            .singleResult as Boolean

    /**
     * The slug of the copy the listings show when [id] is a second listing of the same boat (yacht_listing_twin,
     * audit B17) - for the boat page's canonical link. Null for every other boat.
     */
    private fun listingCanonicalSlug(id: Long): String? {
        val canonicalId =
            entityManager
                .createNativeQuery("SELECT canonical_yacht_id FROM yacht_listing_twin WHERE yacht_id = :id")
                .setParameter("id", id)
                .resultList
                .firstOrNull()
                ?.let { (it as Number).toLong() } ?: return null
        val canonical = yachtRepository.findById(canonicalId).orElse(null) ?: return null
        return SlugUtils.toSlugWithId(canonical.model?.manufacturer?.name, canonical.model?.name, canonical.name, canonicalId)
    }

    fun getYachtAvailability(
        id: Long,
        month: Int?,
        year: Int,
    ): List<YachtAvailabilityDto> {
        getValidYacht(id) // verify if yacht and agency are active, if not exception is thrown

        val reservations =
            if (month == null) {
                // HALF-OPEN (CRIT-3): [Jan-1 of year, Jan-1 of year+1).
                val yearStart = LocalDate.of(year, 1, 1)
                externalReservationRepository.findYachtAvailabilityByYear(id, yearStart, yearStart.plusYears(1))
            } else {
                // endDate is EXCLUSIVE — first day of the next month — so the
                // half-open overlap covers exactly this calendar month.
                val startDate = LocalDate.of(year, month, 1)
                val endDate = startDate.plusMonths(1)
                externalReservationRepository.findYachtAvailabilityByAdjustedYearAndMonth(id, startDate, endDate)
            }

        // Single `now` so lapsed-option demotion is consistent across the page.
        val now = LocalDateTime.now()
        val yachtAvailability =
            reservations.map {
                it.toYachtAvailabilityDto(now)
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

    /**
     * The live listing of the same boat as the retired [id] (yacht_successor, V9_73), for the 1502 answer - read only
     * on that path, so an active boat never pays for it. Fails open: any error (e.g. the table not created yet) means
     * no successor, never a different answer than the plain 1502.
     */
    private fun successorOf(id: Long): YachtSuccessor? =
        runCatching {
            entityManager
                .createNativeQuery(YachtSuccessorLookup.SQL)
                .setParameter("id", id)
                .resultList
                .firstOrNull()
                ?.let { YachtSuccessorLookup.fromRow(it as Array<*>) }
        }.onFailure { log.warn("yacht successor lookup failed for retired yacht {}: {}", id, it.message) }
            .getOrNull()

    private fun getValidYacht(yachtId: Long): Yacht {
        val yacht =
            yachtRepository
                .findById(yachtId)
                .orElseThrow { YachtDoesNotExistException() }

        if (!yacht.sysActive!!) {
            throw YachtNotActiveException(successorOf(yachtId))
        }

        val agency = yacht.agency
        if (yacht.entryType == EntryType.EXTERNAL && (agency == null || !agency.active!! || agency.availabilityBlocked)) {
            throw AgencyNotActiveException()
        }

        return yacht
    }
}
