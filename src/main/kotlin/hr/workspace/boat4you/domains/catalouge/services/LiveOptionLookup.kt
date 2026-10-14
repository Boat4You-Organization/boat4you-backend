package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.OPTION_ECHO_GRACE_HOURS
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One answer to "does a live partner option hold this offer's week?" for every read surface (WS-C, path 11): the
 * detail calendar (`/standard-offers`), the `/offers` booking panel, the boat page (`getYacht`) and the admin card
 * pill (`fetchOfferCharters`, through [optionCutoff]). Before this the rule was copied into each caller and the boat
 * page simply forgot it, so every OPTION / OPTION_WAITING row read FREE there.
 *
 * "Live" = an `external_reservations` OPTION row overlapping the offer half-open (a turnaround day never counts) whose
 * expiry is NULL or later than now minus [OPTION_ECHO_GRACE_HOURS] (partners keep holding past the stated deadline,
 * Vernicos 28.7.2026). One indexed query per call, for one yacht and one window.
 */
@Component
class LiveOptionLookup(
    private val externalReservationRepository: ExternalReservationRepository,
) {
    /** Options that expired before this instant are dead; callers with their own SQL bind this as the cutoff. */
    fun optionCutoff(now: LocalDateTime = LocalDateTime.now()): LocalDateTime = now.minusHours(OPTION_ECHO_GRACE_HOURS)

    /**
     * Loads the live options of [yachtId] overlapping [from, to) once and returns the per-offer test. The window must
     * cover every offer the predicate is asked about (an option overlapping only an offer's out-of-window tail is not
     * loaded); [forOffers] derives it from the offers themselves.
     */
    fun forYachtWindow(
        yachtId: Long,
        from: LocalDate,
        to: LocalDate,
        now: LocalDateTime = LocalDateTime.now(),
    ): (Offer) -> Boolean {
        val liveOptions =
            externalReservationRepository.findOptionsByYachtIdsAndPeriod(
                listOf(yachtId),
                ExternalReservationStatus.OPTION,
                from,
                to,
                optionCutoff(now),
            )
        if (liveOptions.isEmpty()) return NONE
        return { offer -> liveOptions.any { r -> halfOpenOverlap(offer.dateFrom, offer.dateTo, r.dateFrom, r.dateTo) } }
    }

    /**
     * [forYachtWindow] over the envelope of [offers] (earliest start to latest end), falling back to [fallbackFrom] /
     * [fallbackTo] when no offer carries dates. No query at all when there is nothing to ask about.
     */
    fun forOffers(
        yachtId: Long,
        offers: Collection<Offer>,
        fallbackFrom: LocalDate? = null,
        fallbackTo: LocalDate? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): (Offer) -> Boolean {
        val from = offers.mapNotNull { it.dateFrom }.minOrNull() ?: fallbackFrom
        val to = offers.mapNotNull { it.dateTo }.maxOrNull() ?: fallbackTo
        if (offers.isEmpty() || from == null || to == null) return NONE
        return forYachtWindow(yachtId, from, to, now)
    }

    private companion object {
        val NONE: (Offer) -> Boolean = { false }
    }
}
