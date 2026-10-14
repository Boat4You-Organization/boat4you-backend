package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysD3Mode
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysReverifyMode
import hr.workspace.boat4you.common.services.PriceCalculations
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtra
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferPaymentPlan
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.catalouge.utils.ExtraNameNormalizer
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheck
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.PeriodKey
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.service.YachtSyncMutex
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import hr.workspace.boat4you.domains.external.utils.Matchers
import org.openapitools.client.nausys.model.RestFreeYacht
import org.openapitools.client.nausys.model.RestFreeYachtList
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.collections.all
import kotlin.collections.maxOfOrNull
import kotlin.collections.minOfOrNull

@Suppress("LargeClass") // the D3 helpers sit next to the disappearance pass they replace (LEGACY stays byte-identical)
@Service
class NauSysYachtOfferSyncService(
    private val externalMappingService: ExternalMappingService,
    private val externalSystemService: ExternalSystemService,
    private val offerRepository: OfferRepository,
    private val locationQueryingService: LocationQueryingService,
    private val extraRepository: ExtraRepository,
    private val offerExtraRepository: OfferExtraRepository,
    private val externalEquipmentRepository: ExternalEquipmentRepository,
    private val yachtRepository: YachtRepository,
    private val yachtSyncMutex: YachtSyncMutex,
    private val realPriceSwitches: RealPriceSwitches,
    private val periodChecks: PartnerPeriodCheckStore,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        /**
         * Marker for offers that were returned by NauSys for *other* weeks of the same yacht but not for
         * the exact (dateFrom, dateTo) we just synced. Treated as pre-reserved so the yacht still surfaces
         * in search with a "Pre-reserved" badge instead of vanishing.
         *
         * We deliberately do NOT apply this marker when a yacht is completely absent from the response —
         * that signal is too weak (credential-scoped filtering, API hiccups, yacht removed) and produced
         * widespread false positives on cross-agency yachts. The MMK-style pattern of "if we didn't see
         * it, it's gone" is only safe when the API returns everything, which NauSys does not under our
         * single-credential setup. See `CLAUDE.md` > "NauSys under-option handling".
         *
         * On recovery (yacht comes back in a later response for this exact week), updateOffer() overwrites
         * status back to FREE and ext_status to the raw NauSys value — no additional cleanup needed.
         */
        const val SYNTHETIC_DISAPPEARANCE_EXT_STATUS = "SYNTHETIC_DISAPPEARANCE"

        /** NauSys' own "under option" status (mapped to OPTION_WAITING): a real partner hold, never flipped by D3. */
        const val NAUSYS_UNDER_OPTION_EXT_STATUS = "UNDER_OPTION"

        /** D3 per-response breaker: above this share of unpriced yachts in one answer, no strikes (agency escalated). */
        const val D3_MAX_UNPRICED_SHARE = 0.5

        /** `partner_offer_flip_log.reason` values written by D3 (WRITE). */
        const val REASON_ROUTE_GONE = "ROUTE_GONE"
        const val REASON_UNPRICED_TWO_DAY = "UNPRICED_TWO_DAY"
        const val REASON_LEGACY_NOT_RETURNED = "LEGACY_NOT_RETURNED"

        /** Partner statuses that count as a quote of the period (strike closing). */
        private val QUOTE_STATUSES = setOf(OfferStatus.FREE, OfferStatus.OPTION, OfferStatus.OPTION_WAITING)
    }

    /**
     * Transaction unit = ONE YACHT, guarded by [YachtSyncMutex.runExclusiveYachtWrite]
     * (8.7.2026 Hikari exhaustion — see the MMK twin for the full note). A yacht another
     * sync path is writing right now is skipped and heals on the next run; one yacht's
     * failure no longer rolls back the rest of the batch.
     *
     * D3 (real-price DESIGN.md B2, `NAUSYS_D3_MODE`): [d3] null = LEGACY, today's disappearance pass unchanged. LOG =
     * today's writes plus would-flip / would-strike counts per agency and reason in the NAUSYS_D3 run. WRITE = the
     * pass never marks SYNTHETIC_DISAPPEARANCE again: a stale route variant flips to UNAVAILABLE at once (flip-logged,
     * guarded), a FREE-unpriced answer is a strike (status unchanged; a later-day second one flips), our mapping gap
     * leaves the row unchanged, real UNDER_OPTION rows and rows with a reservation_flow are never touched. The reason is
     * decided per stale row: a row whose own route came back FREE without a price is UNPRICED (strike, breaker), only a
     * route absent from the answer while another route of the dates was refreshed is ROUTE_GONE. A WRITE pass re-reads
     * the switch on every answer and stops writing once it is no longer WRITE (rollback by switch).
     *
     * Evidence bookkeeping (only while D3 is not LEGACY or `NAUSYS_REVERIFY` is not OFF; the default switches write
     * nothing new): the rows the partner returns as OPTION / OPTION_WAITING are recorded as QUOTED_OPTION, and a period
     * the partner quotes again (priced FREE / OPTION / OPTION_WAITING, every shown row of the dates refreshed) closes
     * its open strike and hide record - one batch each per answer.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList") // the pre-real-price pass, kept in place so LEGACY stays byte-identical
    fun syncOffers(
        agency: Agency,
        nausysOffers: RestFreeYachtList,
        allAgencyYachts: List<Yacht>,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        // When true, do NOT run the SYNTHETIC_DISAPPEARANCE flip for this interval.
        // Set for the supplemental 7-day Sat->Sat query on min-stay (minimalDuration
        // > 7) seasons: NauSys legitimately quotes no 7-day price there, so the
        // absence of a 7-day offer means "not 7-day bookable", NOT "no longer free".
        // Flipping would wrongly show genuinely-free weeks as pre-reserved.
        skipDisappearance: Boolean = false,
        d3: NauSysD3Run? = null,
    ): Int {
        // Rollback by switch: a WRITE pass stops writing as soon as NAUSYS_D3_MODE is no longer WRITE.
        d3?.recheck(realPriceSwitches.nausysD3Mode)
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getCachedAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
            )
        val allLocationMappings =
            externalMappingService.getCachedAllMappingsByType(Location::class.simpleName.toString(), externalSystem)
        val syncedOffers = mutableSetOf<Long>()
        var skippedCount = 0
        var skippedYachtCount = 0
        var skippedLockedCount = 0
        // Disappearance flips made in this call; returned so the run summary can report "removed".
        var disappeared = 0
        // D6 switch, read once per batch so every extra of the batch is classified alike.
        val optionalOnSite = realPriceSwitches.extrasZeroOptionalOnSite.isOn
        // A2 evidence: periods the partner returned as OPTION / OPTION_WAITING in this answer; periods quoted again.
        val quotedOptions = mutableListOf<PeriodCheck>()
        val requoted = mutableListOf<PeriodKey>()
        // D3 per-response breaker: a reseller losing its pricing must never strike its whole fleet.
        val unpricedBreaker = d3 != null && unpricedYachtShare(nausysOffers, dateFrom, dateTo) > D3_MAX_UNPRICED_SHARE
        if (unpricedBreaker && d3!!.escalate(agency.id!!)) {
            log.error(
                "NauSys D3 [${d3.mode}]: agency ${agency.id} answered more than ${(D3_MAX_UNPRICED_SHARE * 100).toInt()} % of its yachts " +
                    "FREE without a price for $dateFrom..$dateTo - no unpriced strikes from this answer, escalated for a human decision",
            )
        }

        nausysOffers.freeYachts?.groupBy { it.yachtId }?.forEach { (yachtId, nausysYachtOffers) ->
            // Defensive: same NPE risk as MmkYachtOfferSyncService — partner may
            // send yachtId we haven't mapped yet. Skip + warn instead of `!!` chain.
            val mapping = allMappings.find { it.externalId == yachtId }
            val systemYachtId = mapping?.systemId?.takeIf { sysId -> allAgencyYachts.any { it.id == sysId } }
            if (systemYachtId == null) {
                log.warn(
                    "Skipping ${nausysYachtOffers.size} NauSys offers for agency=${agency.id} yachtId=$yachtId " +
                        "— no Yacht mapping or yacht not loaded (mapping.systemId=${mapping?.systemId})",
                )
                skippedYachtCount++
                return@forEach
            }

            try {
                val ran =
                    yachtSyncMutex.runExclusiveYachtWrite(systemYachtId) {
                        // Reload INSIDE the per-yacht transaction so lazy chains
                        // (yacht.agency -> discount) are managed, not detached.
                        val yacht = yachtRepository.findById(systemYachtId).orElse(null) ?: return@runExclusiveYachtWrite

                        val existingYachtOffers = getOffersForYacht(yacht, nausysYachtOffers)

                        // D3: what the answer did for exactly the processed interval (classifies the stale rows below)
                        val exactResults = mutableListOf<OfferUpdateResult>()
                        // existing rows of the interval whose own route came back FREE without a price (D3 per-row reason)
                        val unpricedRowIds = mutableSetOf<Long>()
                        // existing rows the answer refreshed, and the periods it quoted (strike closing)
                        val refreshedIds = mutableSetOf<Long>()
                        val quotedPeriods = mutableSetOf<Pair<LocalDate, LocalDate>>()
                        if (existingYachtOffers.isEmpty()) {
                            nausysYachtOffers.forEach { nausysOffer ->
                                val result = updateOffer(Offer(), yacht, nausysOffer, allLocationMappings, optionalOnSite)
                                if (result != OfferUpdateResult.UPDATED) {
                                    skippedCount++
                                }
                                noteQuotedOption(systemYachtId, nausysOffer, result, quotedOptions)
                                noteQuote(null, nausysOffer, result, refreshedIds, quotedPeriods)
                            }
                        } else {
                            nausysYachtOffers.forEach { nausysOffer ->
                                // Route-aware pairing — same natural key as
                                // uq_offer_yacht_week_product_route (product is a constant for
                                // NauSys rows, so week+route identifies the row). Week-only
                                // matching paired both route variants of a week with the same
                                // row and the update collided with its sibling's key.
                                val pairFromId =
                                    allLocationMappings.find { m -> m.externalId == nausysOffer.locationFromId?.toLong() }?.systemId
                                val pairToId =
                                    allLocationMappings.find { m -> m.externalId == nausysOffer.locationToId?.toLong() }?.systemId
                                val existingOffer =
                                    existingYachtOffers.find {
                                        it.dateFrom == nausysOffer.periodFrom!!.value &&
                                            it.dateTo == nausysOffer.periodTo!!.value &&
                                            it.locationFrom?.id == pairFromId &&
                                            it.locationTo?.id == pairToId
                                    }
                                val result =
                                    if (existingOffer == null) {
                                        updateOffer(Offer(), yacht, nausysOffer, allLocationMappings, optionalOnSite)
                                    } else {
                                        updateOffer(existingOffer, yacht, nausysOffer, allLocationMappings, optionalOnSite)
                                    }
                                // Keep the existing row "alive" (exempt from the disappearance sweep) when the
                                // partner refreshed it OR we skipped for OUR mapping gap. A FREE-but-unpriced
                                // return (reseller lost pricing) is a REAL change → leave it un-marked so the
                                // sweep flips the stale FREE row to OPTION_WAITING ("pre-reserved").
                                if (existingOffer != null && result != OfferUpdateResult.FREE_UNPRICED) {
                                    syncedOffers.add(existingOffer.id!!)
                                }
                                if (result != OfferUpdateResult.UPDATED) skippedCount++
                                noteQuotedOption(systemYachtId, nausysOffer, result, quotedOptions)
                                noteQuote(existingOffer, nausysOffer, result, refreshedIds, quotedPeriods)
                                if (nausysOffer.periodFrom?.value == dateFrom && nausysOffer.periodTo?.value == dateTo) {
                                    exactResults += result
                                    if (existingOffer != null && result == OfferUpdateResult.FREE_UNPRICED) unpricedRowIds += existingOffer.id!!
                                }
                            }
                        }
                        requoted += requotedPeriods(systemYachtId, quotedPeriods, existingYachtOffers, refreshedIds)

                        // Handle offers that existed for this exact (dateFrom, dateTo) but were not refreshed
                        // by the current response (yacht was in response but didn't return an offer for this week).
                        //
                        // SYNTHETIC_OPTION rows (created by NauSysAvailabilitySyncService) are skipped — they'd
                        // re-synthesize on the next availability sync, causing thrashing and brief invisibility.
                        // SYNTHETIC_DISAPPEARANCE rows are likewise skipped so repeated misses don't flip them.
                        //
                        // For FREE offers that disappeared, we mark them as OPTION_WAITING with
                        // SYNTHETIC_DISAPPEARANCE marker so they surface in search as pre-reserved instead of
                        // silently vanishing (UNAVAILABLE is filtered out by yacht_search_view).
                        // Skip entirely for the supplemental 7-day min-stay query (see param doc).
                        if (skipDisappearance) return@runExclusiveYachtWrite
                        val stale =
                            existingYachtOffers
                                .filter { it.dateFrom == dateFrom && it.dateTo == dateTo }
                                .filter {
                                    !syncedOffers.contains(it.id!!) &&
                                        it.status != OfferStatus.UNAVAILABLE &&
                                        it.extStatus != NauSysAvailabilitySyncService.SYNTHETIC_OPTION_EXT_STATUS &&
                                        it.extStatus != SYNTHETIC_DISAPPEARANCE_EXT_STATUS
                                }
                        if (d3 != null && stale.isNotEmpty()) {
                            // per row; strikes before flips, so a flip's hide record never restarts today's strike
                            val byReason =
                                stale
                                    .groupBy { d3RowReason(it, exactResults, unpricedRowIds) }
                                    .entries
                                    .sortedBy { it.key == NauSysD3Run.Reason.ROUTE_GONE }
                            if (d3.writes) {
                                byReason.forEach { (reason, rows) ->
                                    disappeared += d3Write(agency.id!!, systemYachtId, rows, reason, unpricedBreaker, dateFrom, dateTo, d3)
                                }
                                return@runExclusiveYachtWrite
                            }
                            // LOG: counted on the state before today's writes below
                            byReason.forEach { (reason, rows) -> d3Log(agency.id!!, systemYachtId, rows, reason, unpricedBreaker, dateFrom, dateTo, d3) }
                        }
                        stale.forEach { offer ->
                            disappeared++
                            if (offer.status == OfferStatus.FREE) {
                                offer.status = OfferStatus.OPTION_WAITING
                                offer.extStatus = SYNTHETIC_DISAPPEARANCE_EXT_STATUS
                                log.info(
                                    "Marked offer ${offer.id} (yacht ${offer.yacht?.id}) as SYNTHETIC_DISAPPEARANCE " +
                                        "for $dateFrom-$dateTo (in response, but no offer for this week)",
                                )
                            } else {
                                // Preserve legacy UNAVAILABLE transition for non-FREE statuses
                                offer.status = OfferStatus.UNAVAILABLE
                            }
                            offerRepository.save(offer)
                        }
                    }
                if (!ran) skippedLockedCount++
            } catch (e: Exception) {
                log.error("NauSys offer sync failed for yacht=$systemYachtId (agency=${agency.id}) — yacht skipped, batch continues", e)
            }
        }

        if (skippedLockedCount > 0) {
            log.info("NauSys offer sync for agency ${agency.id}: skipped $skippedLockedCount yachts already being written by another sync")
        }
        if (skippedCount > 0) {
            log.warn("NauSys offer sync for agency ${agency.id}: skipped $skippedCount offers (missing Location mapping or unpriced FREE)")
        }
        if (skippedYachtCount > 0) {
            log.warn("NauSys offer sync for agency ${agency.id}: skipped $skippedYachtCount yachts due to missing Yacht mappings")
        }
        if (d3 != null || realPriceSwitches.nausysReverify != NauSysReverifyMode.OFF) {
            recordQuotedOptions(quotedOptions)
            closeStrikesOnQuote(requoted)
        }
        d3?.responseDone()
        return disappeared
    }

    /** A2 evidence: a row the partner returned as OPTION / OPTION_WAITING and we wrote (recorded outside the defaults). */
    private fun noteQuotedOption(
        yachtId: Long,
        nausysOffer: RestFreeYacht,
        result: OfferUpdateResult,
        into: MutableList<PeriodCheck>,
    ) {
        val status = OfferStatus.fromNausysValue(nausysOffer.status)
        val quotedOption = result == OfferUpdateResult.UPDATED && (status == OfferStatus.OPTION || status == OfferStatus.OPTION_WAITING)
        val from = nausysOffer.periodFrom?.value
        val to = nausysOffer.periodTo?.value
        if (quotedOption && from != null && to != null) {
            into += PeriodCheck(yachtId, from, to, PeriodCheckPriority.SAMPLE, PeriodCheckResult.QUOTED_OPTION)
        }
    }

    /** One JDBC batch per answer; bookkeeping never breaks the sync. */
    private fun recordQuotedOptions(checks: List<PeriodCheck>) {
        if (checks.isEmpty()) return
        runCatching { periodChecks.recordCheckedBatch(ExternalSystemEnum.NAUSYS, checks.distinct()) }
            .onFailure { log.warn("NauSys QUOTED_OPTION bookkeeping: ${checks.size} periods not recorded: ${it.message}") }
    }

    /**
     * Strike-closing evidence of one partner row: the existing row it refreshed, and its period when the partner quoted
     * it (priced FREE, OPTION or OPTION_WAITING) and we wrote it.
     */
    private fun noteQuote(
        existingOffer: Offer?,
        nausysOffer: RestFreeYacht,
        result: OfferUpdateResult,
        refreshedIds: MutableSet<Long>,
        quotedPeriods: MutableSet<Pair<LocalDate, LocalDate>>,
    ) {
        if (result != OfferUpdateResult.UPDATED) return
        existingOffer?.id?.let { refreshedIds += it }
        val from = nausysOffer.periodFrom?.value
        val to = nausysOffer.periodTo?.value
        if (OfferStatus.fromNausysValue(nausysOffer.status) in QUOTE_STATUSES && from != null && to != null) quotedPeriods += from to to
    }

    /**
     * The quoted periods whose every shown row (FREE / OPTION / OPTION_WAITING, not SYNTHETIC_OPTION) of exactly those
     * dates this answer refreshed: positive partner evidence that closes an open strike. A shown row the answer left
     * stale (a route gone, a route unpriced, our marker) keeps the period's strike open for the reverifier.
     */
    private fun requotedPeriods(
        yachtId: Long,
        quotedPeriods: Set<Pair<LocalDate, LocalDate>>,
        existing: List<Offer>,
        refreshedIds: Set<Long>,
    ): List<PeriodKey> =
        quotedPeriods
            .filter { (from, to) ->
                existing.none { o ->
                    o.dateFrom == from && o.dateTo == to && o.id !in refreshedIds && o.status in QUOTE_STATUSES &&
                        o.extStatus != NauSysAvailabilitySyncService.SYNTHETIC_OPTION_EXT_STATUS
                }
            }.map { (from, to) -> PeriodKey(yachtId, from, to) }

    /** One JDBC batch per answer (only rows with an open strike or hide are written); never breaks the sync. */
    private fun closeStrikesOnQuote(periods: List<PeriodKey>) {
        if (periods.isEmpty()) return
        runCatching { periodChecks.closeStrikesOnQuoteBatch(ExternalSystemEnum.NAUSYS, periods) }
            .onFailure { log.warn("NauSys strike closing on quote: ${periods.size} periods not recorded: ${it.message}") }
    }

    /**
     * Share of the yachts in [answer] that came back FREE without a price for exactly [dateFrom]..[dateTo] (the D3
     * per-response breaker reads the answer alone, before any write).
     */
    internal fun unpricedYachtShare(
        answer: RestFreeYachtList,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): Double {
        val byYacht = answer.freeYachts.orEmpty().groupBy { it.yachtId }
        if (byYacht.isEmpty()) return 0.0
        val unpriced =
            byYacht.values.count { rows ->
                rows.any { r -> r.periodFrom?.value == dateFrom && r.periodTo?.value == dateTo && isFreeUnpriced(r) }
            }
        return unpriced.toDouble() / byYacht.size
    }

    private fun isFreeUnpriced(r: RestFreeYacht): Boolean {
        val price = r.price?.clientPrice?.toBigDecimalOrNull()
        return OfferStatus.fromNausysValue(r.status) == OfferStatus.FREE && (price == null || price <= BigDecimal.ZERO)
    }

    /**
     * Why one stale row of the interval was not refreshed: our mapping gap first (we cannot tell which route an
     * unmapped row was), then the row's own route answered FREE without a price, then the yacht-level reason.
     */
    private fun d3RowReason(
        offer: Offer,
        exactResults: List<OfferUpdateResult>,
        unpricedRowIds: Set<Long>,
    ): NauSysD3Run.Reason =
        when {
            OfferUpdateResult.SKIPPED_MISSING_LOCATION in exactResults -> NauSysD3Run.Reason.MAPPING_GAP
            offer.id in unpricedRowIds -> NauSysD3Run.Reason.UNPRICED
            else -> d3Reason(exactResults)
        }

    /** Why the yacht's rows of the interval were not refreshed: our mapping gap first, then route, unpriced, absent. */
    private fun d3Reason(exactResults: List<OfferUpdateResult>): NauSysD3Run.Reason =
        when {
            OfferUpdateResult.SKIPPED_MISSING_LOCATION in exactResults -> NauSysD3Run.Reason.MAPPING_GAP
            OfferUpdateResult.UPDATED in exactResults -> NauSysD3Run.Reason.ROUTE_GONE
            OfferUpdateResult.FREE_UNPRICED in exactResults -> NauSysD3Run.Reason.UNPRICED
            else -> NauSysD3Run.Reason.NOT_RETURNED
        }

    /** FREE / OPTION rows D3 may decide on (never a real UNDER_OPTION, never our SYNTHETIC markers). */
    private fun d3Decidable(offer: Offer): Boolean = (offer.status == OfferStatus.FREE || offer.status == OfferStatus.OPTION) && offer.extStatus != NAUSYS_UNDER_OPTION_EXT_STATUS

    /** The rows today's legacy branch flips to UNAVAILABLE that D3 still flips: other non-FREE statuses, guarded. */
    private fun d3LegacyNonFree(offer: Offer): Boolean =
        !d3Decidable(offer) &&
            offer.status != OfferStatus.FREE &&
            offer.status != OfferStatus.OPTION_WAITING &&
            offer.extStatus != NAUSYS_UNDER_OPTION_EXT_STATUS

    /**
     * D3 WRITE for the stale rows of one yacht and interval, inside the yacht's write transaction. Returns the number
     * of rows whose status changed.
     */
    @Suppress("LongParameterList", "LongMethod", "ReturnCount") // the agency, yacht, rows, evidence, breaker, interval and run
    private fun d3Write(
        agencyId: Long,
        yachtId: Long,
        stale: List<Offer>,
        reason: NauSysD3Run.Reason,
        unpricedBreaker: Boolean,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        d3: NauSysD3Run,
    ): Int {
        d3.count(agencyId, reason, stale.size)
        if (reason == NauSysD3Run.Reason.MAPPING_GAP) {
            // OUR bug, not partner evidence: nothing changes, the counter (unknown) and the per-agency WARN line report it.
            d3.tally.unknown.addAndGet(stale.size)
            log.debug("NauSys D3: yacht=$yachtId $dateFrom..$dateTo ${stale.size} rows left unchanged (location mapping gap)")
            return 0
        }
        var changed = 0
        // Other non-FREE statuses keep today's UNAVAILABLE transition, guarded (no booking in progress).
        stale.filter(::d3LegacyNonFree).forEach { offer ->
            if (!offerRepository.existsReservationFlowForOffer(offer.id!!)) {
                offer.status = OfferStatus.UNAVAILABLE
                offerRepository.save(offer)
                changed++
            }
        }
        val decidable = stale.filter(::d3Decidable)
        if (decidable.isEmpty()) return changed
        when (reason) {
            NauSysD3Run.Reason.ROUTE_GONE -> {
                // Positive evidence for these dates: the partner quoted the yacht on another route. Flip at once.
                val flipped = decidable.sumOf { flipRow(it, REASON_ROUTE_GONE, null, d3) }
                changed += hidden(yachtId, dateFrom, dateTo, flipped, d3)
            }
            NauSysD3Run.Reason.UNPRICED -> {
                if (unpricedBreaker) return changed
                val firstEmptyOn =
                    periodChecks.recordFirstStrike(
                        ExternalSystemEnum.NAUSYS,
                        yachtId,
                        dateFrom,
                        dateTo,
                        PeriodCheckPriority.ON_DEMAND_OR_BOOKING,
                        PeriodCheckResult.UNPRICED,
                        d3.today,
                    )
                d3.tally.empty.incrementAndGet()
                if (!firstEmptyOn.isBefore(d3.today)) {
                    d3.tally.firstStrikes.incrementAndGet()
                } else {
                    val flipped = decidable.sumOf { flipRow(it, REASON_UNPRICED_TWO_DAY, firstEmptyOn, d3) }
                    if (flipped == 0) periodChecks.clearStrike(ExternalSystemEnum.NAUSYS, yachtId, dateFrom, dateTo)
                    changed += hidden(yachtId, dateFrom, dateTo, flipped, d3)
                }
            }
            NauSysD3Run.Reason.NOT_RETURNED -> {
                // A first strike for the reverifier (P3), which confirms with an exact single-yacht call; no status change.
                val firstEmptyOn =
                    periodChecks.recordFirstStrike(
                        ExternalSystemEnum.NAUSYS,
                        yachtId,
                        dateFrom,
                        dateTo,
                        PeriodCheckPriority.ON_DEMAND_OR_BOOKING,
                        PeriodCheckResult.EMPTY,
                        d3.today,
                    )
                if (!firstEmptyOn.isBefore(d3.today)) d3.tally.firstStrikes.incrementAndGet()
            }
            NauSysD3Run.Reason.MAPPING_GAP -> {}
        }
        return changed
    }

    private fun flipRow(
        offer: Offer,
        reason: String,
        firstEmptyOn: LocalDate?,
        d3: NauSysD3Run,
    ): Int =
        offerRepository.markNauSysPeriodUnavailable(
            offer.yacht!!.id!!,
            offer.dateFrom!!,
            offer.dateTo!!,
            offer.id,
            false,
            d3.runId,
            ReverifyJobs.NAUSYS_D3,
            reason,
            firstEmptyOn,
            d3.today,
        )

    private fun hidden(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        flipped: Int,
        d3: NauSysD3Run,
    ): Int {
        if (flipped > 0) {
            periodChecks.markHidden(ExternalSystemEnum.NAUSYS, yachtId, dateFrom, dateTo, flipped, d3.today)
            d3.tally.hiddenPeriods.incrementAndGet()
            d3.tally.hiddenRows.addAndGet(flipped)
        }
        return flipped
    }

    /** D3 LOG: what WRITE would do with the stale rows, counted before today's legacy writes run. */
    @Suppress("LongParameterList") // the agency, yacht, rows, evidence, breaker, interval and run
    private fun d3Log(
        agencyId: Long,
        yachtId: Long,
        stale: List<Offer>,
        reason: NauSysD3Run.Reason,
        unpricedBreaker: Boolean,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        d3: NauSysD3Run,
    ) {
        d3.count(agencyId, reason, stale.size)
        val decidable = stale.filter(::d3Decidable)
        when (reason) {
            NauSysD3Run.Reason.MAPPING_GAP -> d3.tally.unknown.addAndGet(stale.size)
            NauSysD3Run.Reason.ROUTE_GONE ->
                d3.tally.wouldFlipRows.addAndGet(
                    decidable.sumOf { offerRepository.countNauSysPeriodFlippable(yachtId, dateFrom, dateTo, it.id, false) },
                )
            NauSysD3Run.Reason.UNPRICED ->
                if (!unpricedBreaker && decidable.isNotEmpty()) {
                    d3.tally.empty.incrementAndGet()
                    d3.tally.firstStrikes.incrementAndGet()
                }
            NauSysD3Run.Reason.NOT_RETURNED -> if (decidable.isNotEmpty()) d3.tally.firstStrikes.incrementAndGet()
        }
    }

    /**
     * Transaction unit = ONE YACHT under the advisory write lock — same rationale as
     * [syncOffers]. A yacht another sync is writing right now is skipped.
     *
     * Outside the default switches (`NAUSYS_REVERIFY` not OFF or `NAUSYS_D3_MODE` not LEGACY) a period the partner
     * quotes again here closes its open strike and hide record, exactly like [syncOffers] (one batch per call).
     */
    fun syncOffersForAsync(nausysAllOffers: List<RestFreeYacht>) {
        val yachtExternalIds = nausysAllOffers.map { it.yachtId!! }.toList()
        if (yachtExternalIds.isEmpty()) {
            log.warn("No yacht external IDs found in Nausys offers, skipping sync.")
            return
        }

        val allYachts =
            yachtRepository.findByExternalIdsAndExternalSystemId(
                yachtExternalIds,
                ExternalSystemEnum.NAUSYS.value.toLong(),
            )
        val allYachtMappings =
            externalMappingService.findAllByTypeAndExternalSystemAndExternalIdIn(
                Yacht::class.simpleName.toString(),
                ExternalSystemEnum.NAUSYS.value,
                yachtExternalIds,
            )
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allLocationMappings =
            externalMappingService.getCachedAllMappingsByType(Location::class.simpleName.toString(), externalSystem)

        var skippedCount = 0
        var skippedLockedCount = 0
        // D6 switch, read once per batch so every extra of the batch is classified alike.
        val optionalOnSite = realPriceSwitches.extrasZeroOptionalOnSite.isOn
        val requoted = mutableListOf<PeriodKey>()
        nausysAllOffers.groupBy { it.yachtId }?.forEach { (nausysYachtId, nausysOffers) ->
            val mapping = allYachtMappings.find { it.externalId == nausysYachtId!! } ?: return@forEach
            val systemYachtId =
                mapping.systemId?.takeIf { sysId -> allYachts.any { it.id == sysId } } ?: return@forEach

            try {
                val ran =
                    yachtSyncMutex.runExclusiveYachtWrite(systemYachtId) {
                        // Reload INSIDE the per-yacht transaction so lazy chains
                        // (yacht.agency -> discount) are managed, not detached.
                        val yacht = yachtRepository.findById(systemYachtId).orElse(null) ?: return@runExclusiveYachtWrite

                        val existingYachtOffers = getOffersForYacht(yacht, nausysOffers)
                        val refreshedIds = mutableSetOf<Long>()
                        val quotedPeriods = mutableSetOf<Pair<LocalDate, LocalDate>>()

                        nausysOffers.forEach { nausysOffer ->
                            val existingOffer = asyncRoutePair(existingYachtOffers, nausysOffer, allLocationMappings)
                            val result =
                                if (existingOffer == null) {
                                    updateOffer(Offer(), yacht, nausysOffer, allLocationMappings, optionalOnSite)
                                } else {
                                    updateOffer(existingOffer, yacht, nausysOffer, allLocationMappings, optionalOnSite)
                                }
                            if (result != OfferUpdateResult.UPDATED) skippedCount++
                            noteQuote(existingOffer, nausysOffer, result, refreshedIds, quotedPeriods)
                        }
                        requoted += requotedPeriods(systemYachtId, quotedPeriods, existingYachtOffers, refreshedIds)
                    }
                if (!ran) skippedLockedCount++
            } catch (e: Exception) {
                log.error("NauSys async offer sync failed for yacht=$systemYachtId — yacht skipped, batch continues", e)
            }
        }

        if (skippedLockedCount > 0) {
            log.info("NauSys async offer sync: skipped $skippedLockedCount yachts already being written by another sync")
        }
        if (skippedCount > 0) {
            log.warn("NauSys async offer sync: skipped $skippedCount offers due to missing Location mappings")
        }
        if (requoted.isNotEmpty() && evidenceSwitchesOn()) closeStrikesOnQuote(requoted)
    }

    /** True when a NauSys evidence switch is away from its default (`NAUSYS_REVERIFY` OFF, `NAUSYS_D3_MODE` LEGACY). */
    private fun evidenceSwitchesOn(): Boolean =
        realPriceSwitches.nausysReverify != NauSysReverifyMode.OFF || realPriceSwitches.nausysD3Mode != NauSysD3Mode.LEGACY

    /**
     * Route-aware pairing of [syncOffersForAsync] — same fix as the scheduled path: the natural key is (yacht, week,
     * product, ROUTE); week-only matching pairs both route variants of a week with the same row and the update
     * collides with its sibling's key.
     */
    private fun asyncRoutePair(
        existingYachtOffers: List<Offer>,
        nausysOffer: RestFreeYacht,
        allLocationMappings: List<ExternalMapping>,
    ): Offer? {
        val pairFromId = allLocationMappings.find { m -> m.externalId == nausysOffer.locationFromId?.toLong() }?.systemId
        val pairToId = allLocationMappings.find { m -> m.externalId == nausysOffer.locationToId?.toLong() }?.systemId
        return existingYachtOffers.find {
            it.dateFrom == nausysOffer.periodFrom!!.value!! &&
                it.dateTo == nausysOffer.periodTo!!.value!! &&
                it.locationFrom?.id == pairFromId &&
                it.locationTo?.id == pairToId
        }
    }

    private fun getOffersForYacht(
        yacht: Yacht,
        nausysYachtOffers: List<RestFreeYacht>,
    ): List<Offer> {
        val minDateFrom = nausysYachtOffers.minOfOrNull { it.periodFrom!!.value!! }
        val maxDateTo = nausysYachtOffers.maxOfOrNull { it.periodTo!!.value!! }
        val allDatesEqual =
            nausysYachtOffers.all { it.periodFrom!!.value!! == minDateFrom && it.periodTo!!.value!! == maxDateTo }

        // use for better db index usage
        return if (allDatesEqual) {
            offerRepository.findAllByYachtAndDateFromAndDateTo(yacht, minDateFrom!!, maxDateTo!!)
        } else {
            offerRepository.findAllByYachtAndDateFromGreaterThanEqualAndDateToLessThanEqual(
                yacht,
                minDateFrom!!,
                maxDateTo!!,
            )
        }
    }

    private enum class OfferUpdateResult { UPDATED, SKIPPED_MISSING_LOCATION, FREE_UNPRICED }

    /**
     * @return [OfferUpdateResult.UPDATED] when the offer was saved;
     * [OfferUpdateResult.SKIPPED_MISSING_LOCATION] when a required Location mapping/row is missing
     * (OUR mapping gap, not a partner change — the caller MUST keep the existing row alive, never
     * deactivate it); [OfferUpdateResult.FREE_UNPRICED] when the partner returned this FREE week
     * with no price (e.g. a reseller that lost pricing) — a REAL change the caller must propagate
     * by NOT marking the row alive, so the disappearance pass flips the stale FREE row to
     * "pre-reserved" (OPTION_WAITING).
     */
    private fun updateOffer(
        offer: Offer,
        yacht: Yacht,
        nausysOffer: RestFreeYacht,
        allLocationMappings: List<ExternalMapping>,
        optionalOnSite: Boolean,
    ): OfferUpdateResult {
        // A bookable (FREE) offer must carry a real price. NauSys occasionally returns a
        // free yacht with clientPrice null/0 — no price-list entry for that period, or a
        // reseller copy that doesn't own the pricing. Persisting it surfaces an
        // "AVAILABLE 0 €" week on the site (and the `clientPrice!!` below would NPE on
        // null and abort the whole agency batch). Treat "free but unpriced" as "not
        // bookable here" → skip and leave a calendar gap. OPTION/UNAVAILABLE markers may
        // legitimately have no price, so the guard is scoped to FREE only.
        val nausysClientPrice = nausysOffer.price?.clientPrice?.toBigDecimal()
        if (OfferStatus.fromNausysValue(nausysOffer.status) == OfferStatus.FREE &&
            (nausysClientPrice == null || nausysClientPrice <= BigDecimal.ZERO)
        ) {
            // Per-offer detail at DEBUG (44k WARN/week on cusma2); the per-agency summary in
            // syncOffers already counts these as skipped.
            log.debug(
                "Skipping unpriced FREE NauSys offer for yacht=${yacht.id} " +
                    "${nausysOffer.periodFrom?.value}..${nausysOffer.periodTo?.value} (clientPrice=$nausysClientPrice)",
            )
            return OfferUpdateResult.FREE_UNPRICED
        }

        val locationFromMapping =
            allLocationMappings.find { location -> location.externalId == nausysOffer.locationFromId!!.toLong() }
        val locationFrom =
            locationFromMapping?.systemId?.let { locationQueryingService.getCachedLocationById(it) }
        val locationToMapping =
            allLocationMappings.find { location -> location.externalId == nausysOffer.locationToId!!.toLong() }
        val locationTo =
            locationToMapping?.systemId?.let { locationQueryingService.getCachedLocationById(it) }

        // Without both Locations the offer fails @NotNull pre-update validation on the next
        // session auto-flush, which aborts the entire transaction. Skip silently with a warn
        // so one broken upstream mapping doesn't wipe an agency's batch.
        if (locationFrom == null || locationTo == null) {
            log.warn(
                "Skipping NauSys offer for yacht=${yacht.id}: missing Location " +
                    "(locationFromId=${nausysOffer.locationFromId} → mapping=${locationFromMapping?.systemId}, " +
                    "locationToId=${nausysOffer.locationToId} → mapping=${locationToMapping?.systemId})",
            )
            return OfferUpdateResult.SKIPPED_MISSING_LOCATION
        }

        offer.yacht = yacht
        offer.status = OfferStatus.fromNausysValue(nausysOffer.status)
        offer.extStatus = nausysOffer.status
        offer.product = CharterType.UNKNOWN
        offer.locationFrom = locationFrom
        offer.locationTo = locationTo
        offer.dateFrom = nausysOffer.periodFrom!!.value
        offer.dateTo = nausysOffer.periodTo!!.value
        offer.checkin = nausysOffer.checkIn ?: yacht.defaultCheckin ?: "Contact for details"
        offer.checkout = nausysOffer.checkOut ?: yacht.defaultCheckout ?: "Contact for details"
        offer.type = OfferType.getFromDates(offer.dateFrom!!, offer.dateTo!!)

        offer.deposit = nausysOffer.price!!.depositAmount?.toBigDecimal()
        offer.depositInsured =
            nausysOffer.price!!.depositWhenInsuredAmount?.toBigDecimal()

        val discounts = nausysOffer.price!!.discounts?.map { it.amount?.toBigDecimal() ?: BigDecimal.ZERO }
        offer.extTotalDiscount = if (!discounts.isNullOrEmpty()) discounts.sumOf { it } else null
        offer.extDiscountPerc = null
        offer.totalDiscount = null

        offer.extBasePrice = nausysOffer.price!!.priceListPrice?.toBigDecimal()
        offer.extClientPrice = nausysOffer.price!!.clientPrice?.toBigDecimal()
        offer.extTotalPrice = nausysOffer.totalPriceWithExtras?.toBigDecimal()

        val clientPrice =
            PriceCalculations.calculateClientPrice(
                nausysOffer.price!!.clientPrice!!.toBigDecimal(),
                yacht.agency!!.getDiscountOrZero(),
                yacht.excludeDiscount != true,
            )
        offer.clientPrice = clientPrice
        offer.agencyCommission = nausysOffer.price!!.clientPrice!!.toBigDecimal().minus(clientPrice)
        // Broker commission — partner's exact per-offer figure. Nausys
        // exposes it as `price.agencyCommission` (a string in their DTO).
        // Null when the partner didn't include it on this offer (rare).
        offer.brokerCommission = nausysOffer.price!!.agencyCommission?.toBigDecimal()

        val obligatoryExtrasPrice =
            nausysOffer.obligatoryExtras
                ?.filter { it.calculationType?.value != "SEPARATE_PAYMENT" }
                ?.sumOf { it.totalPrice?.toBigDecimal() ?: BigDecimal.ZERO }
                ?: BigDecimal.ZERO
        offer.totalPrice = clientPrice + obligatoryExtrasPrice
        offer.obligatoryExtrasPrice = obligatoryExtrasPrice

        offerRepository.save(offer)

        handleExtras(offer, nausysOffer, optionalOnSite)

        // Partner payment plan = source of truth for installment timing.
        // Re-enabled 1.5.2026 — see MmkYachtOfferSyncService for the full note.
        if (!nausysOffer.paymentPlans.isNullOrEmpty()) {
            handlePaymentPlans(offer, nausysOffer)
        } else {
            offer.offerPaymentPlans.clear()
            offerRepository.save(offer)
        }
        return OfferUpdateResult.UPDATED
    }

    /**
     * There is a lot of duplicate code here, but it has different type so its easier to keep it like this
     */
    private fun handleExtras(
        offer: Offer,
        nausysOffer: RestFreeYacht,
        optionalOnSite: Boolean,
    ) {
        val allExtras = extraRepository.findAll()
        val allExternalEquipment =
            externalEquipmentRepository.getCachedByExternalSystemId(ExternalSystemEnum.NAUSYS.value)
        val matchedIds = mutableSetOf<Long>()

        nausysOffer.obligatoryExtras?.forEach { nausysExtra ->
            // for each nausys equipment, find the external nausys equipment and try to match it with our equipment
            val externalEquipmentMatch =
                allExternalEquipment.firstOrNull { eq -> eq.externalId == nausysExtra.serviceId!! && eq.type == ExternalEquipmentType.SERVICE }
            if (externalEquipmentMatch == null) {
                log.error("Nausys equipment not found for Nausys extras: $nausysExtra")
                return@forEach
            }
            val boat4youEquipmentMatch =
                allExtras.firstOrNull { eq ->
                    Matchers.extrasNameMatch(eq.getMatchKeysList(), externalEquipmentMatch.name)
                }
            // Per-offer condition note from NauSys (e.g. "must be paid 14 days
            // before embarkation"). Mirror MMK obligatory mapper which copies
            // `mmkExtra.description` — we do the same so the "small print"
            // surfaces under the extras name on /boat detail and admin offer.
            val nausysCondition =
                nausysExtra.condition
                    ?.let {
                        it.textEN ?: it.textHR ?: it.textIT ?: it.textDE
                    }?.takeIf { it.isNotBlank() }

            val equipmentAlreadyOnOffer =
                offer.offerExtras.find { ex ->
                    ex.externalId == nausysExtra.id
                }

            val obligPrice = nausysExtra.totalPrice?.toBigDecimal()
            val obligPayable = nausysExtra.calculationType?.value == "SEPARATE_PAYMENT"
            // Obligatory ADVANCE_PAYMENT items are part of NauSys's advance total
            // (totalPriceWithExtras), so they are paid with the booking here too
            // (26.9.2026, see fromNausysOfferObligatory).
            val obligPaymentType =
                hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType.fromNausysOfferObligatory(
                    calculationType = nausysExtra.calculationType?.value,
                    price = obligPrice,
                )
            if (equipmentAlreadyOnOffer != null) {
                equipmentAlreadyOnOffer.extras = boat4youEquipmentMatch
                equipmentAlreadyOnOffer.price = obligPrice
                equipmentAlreadyOnOffer.payableInBase = obligPayable
                equipmentAlreadyOnOffer.paymentType = obligPaymentType
                equipmentAlreadyOnOffer.externalUnit = nausysExtra.priceMeasureId.toString()
                equipmentAlreadyOnOffer.description = nausysCondition
                matchedIds.add(equipmentAlreadyOnOffer.id!!)
                return@forEach
            }

            val offerExtra = OfferExtra()
            offerExtra.extras = boat4youEquipmentMatch
            offerExtra.name = ExtraNameNormalizer.normalize(externalEquipmentMatch.name)
            offerExtra.externalId = nausysExtra.id!!
            offerExtra.offer = offer
            offerExtra.price = obligPrice
            offerExtra.payableInBase = obligPayable
            offerExtra.paymentType = obligPaymentType
            offerExtra.unit = ExtrasUnitType.PER_BOOKING
            offerExtra.externalUnit = nausysExtra.priceMeasureId.toString()
            offerExtra.obligatory = true
            offerExtra.description = nausysCondition

            offerExtraRepository.save(offerExtra)
            offer.offerExtras.add(offerExtra)
            matchedIds.add(offerExtra.id!!)
        }

        nausysOffer.additionalExtras?.forEach { nausysExtra ->
            val externalEquipmentMatch =
                allExternalEquipment.firstOrNull { eq -> eq.externalId == nausysExtra.extraId!! && eq.type?.name == nausysExtra.extrasType?.value }
            if (externalEquipmentMatch == null) {
                log.error("Nausys equipment not found for Nausys extras: $nausysExtra")
                return@forEach
            }
            val boat4youEquipmentMatch =
                allExtras.firstOrNull { eq ->
                    Matchers.extrasNameMatch(eq.getMatchKeysList(), externalEquipmentMatch.name)
                }
            val nausysCondition =
                nausysExtra.condition
                    ?.let {
                        it.textEN ?: it.textHR ?: it.textIT ?: it.textDE
                    }?.takeIf { it.isNotBlank() }

            val equipmentAlreadyOnOffer =
                offer.offerExtras.find { ex ->
                    ex.externalId == nausysExtra.id
                }

            val addPrice = nausysExtra.totalPrice?.toBigDecimal()
            val addPayable = nausysExtra.calculationType?.value == "SEPARATE_PAYMENT"
            val addPaymentType =
                hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType.fromNausysCalculationType(
                    calculationType = nausysExtra.calculationType?.value,
                    price = addPrice,
                    obligatory = false,
                    optionalOnSite = optionalOnSite,
                )
            if (equipmentAlreadyOnOffer != null) {
                equipmentAlreadyOnOffer.extras = boat4youEquipmentMatch
                equipmentAlreadyOnOffer.price = addPrice
                equipmentAlreadyOnOffer.payableInBase = addPayable
                equipmentAlreadyOnOffer.paymentType = addPaymentType
                equipmentAlreadyOnOffer.externalUnit = nausysExtra.priceMeasureId.toString()
                equipmentAlreadyOnOffer.description = nausysCondition
                matchedIds.add(equipmentAlreadyOnOffer.id!!)
                return@forEach
            }

            val offerExtra = OfferExtra()
            offerExtra.extras = boat4youEquipmentMatch
            offerExtra.name = ExtraNameNormalizer.normalize(externalEquipmentMatch.name)
            offerExtra.externalId = nausysExtra.id!!
            offerExtra.offer = offer
            offerExtra.price = addPrice
            offerExtra.payableInBase = addPayable
            offerExtra.paymentType = addPaymentType
            offerExtra.unit = ExtrasUnitType.PER_BOOKING
            offerExtra.externalUnit = nausysExtra.priceMeasureId.toString()
            offerExtra.obligatory = false
            offerExtra.description = nausysCondition

            offerExtraRepository.save(offerExtra)
            offer.offerExtras.add(offerExtra)
            matchedIds.add(offerExtra.id!!)
        }

        val toRemove = mutableListOf<OfferExtra>()
        offer.offerExtras.forEach { offerExtra ->
            if (matchedIds.none { it == offerExtra.id }) {
                toRemove.add(offerExtra)
            }
        }
        if (toRemove.isNotEmpty()) {
            offer.offerExtras.removeAll(toRemove)
            offerExtraRepository.deleteAll(toRemove)
        }
    }

    private fun handlePaymentPlans(
        offer: Offer,
        nausysOffer: RestFreeYacht,
    ) {
        // Create a set of dates from incoming payment plans for efficient lookup
        val incomingDates = nausysOffer.paymentPlans?.map { it.date?.value }?.toSet() ?: emptySet()

        // Remove payment plans that are not in the incoming list
        offer.offerPaymentPlans.removeIf { existingPlan ->
            existingPlan.date !in incomingDates
        }

        // Add or update payment plans
        nausysOffer.paymentPlans?.forEach { incomingPlan ->
            val planDate = incomingPlan.date?.value
            val existingPlan = offer.offerPaymentPlans.find { it.date == planDate }

            // Nausys percentage is whole-number percent (e.g. 20.00 means 20%),
            // not a decimal fraction. Divide by 100 before multiplying or the
            // stored amount ends up 100× the actual price (offer 319593 case:
            // 20% × 4,950 € rendered as 99,000 € until this fix).
            val amount =
                incomingPlan.percentage?.let {
                    it.divide(BigDecimal(100), 4, RoundingMode.HALF_UP).multiply(
                        offer.clientPrice ?: BigDecimal.ZERO,
                    )
                }

            if (existingPlan != null) {
                // Update existing plan
                existingPlan.amount = amount
                existingPlan.percentage = incomingPlan.percentage
            } else {
                // Add new plan
                val offerPaymentPlan = OfferPaymentPlan()
                offerPaymentPlan.offer = offer
                offerPaymentPlan.date = planDate
                offerPaymentPlan.amount = amount
                offerPaymentPlan.percentage = incomingPlan.percentage
                offer.offerPaymentPlans.add(offerPaymentPlan)
            }
        }

        offerRepository.save(offer)
    }
}
