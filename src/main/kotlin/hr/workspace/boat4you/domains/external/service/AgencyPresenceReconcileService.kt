package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySourceRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Agency half of "kako na njima, tako kod nas" (Mario 5.7.2026): the partner's company list
 * IS our agency list. Each catalogue sync creates/reactivates the companies the partner
 * returns; this service is the removal half — an active agency whose PRIMARY source belongs
 * to the synced system but whose company id is missing from the partner's response gets
 * active=false, which drops all its yachts from yacht_search_view on the next matview refresh.
 *
 * Removal is reversible: rows are deactivated (never deleted — reservations reference them)
 * and stamped syncDeactivatedBy=<system> so the SAME system's create/reactivate pass can bring
 * them back the day the partner lists them again, while a manual admin deactivation
 * (syncDeactivatedBy=NULL) stays untouched and the other system's mirror never ping-pongs it.
 *
 * Guards mirror the reservation reconcile ([ExternalAvailabilityReconcileService]):
 *  - EMPTY-GUARD: zero companies = no-data, not "everyone left" — deactivate nothing.
 *  - CIRCUIT BREAKER: a real churn wave is small; a large absent fraction almost always
 *    means a truncated-but-parseable partner response — refuse to mass-deactivate
 *    ([PartnerWithdrawalGuard.maxWithdrawable]).
 * Must only run after a SUCCESSFUL companies fetch (callers pass the parsed response).
 */
@Service
class AgencyPresenceReconcileService(
    private val agencyRepository: AgencyRepository,
    private val agencySourceRepository: AgencySourceRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transactional
    fun deactivateAgenciesAbsentFromPartner(
        externalSystemId: Int,
        presentExternalIds: Set<Long>,
    ) {
        if (presentExternalIds.isEmpty()) {
            log.warn(
                "Skip agency absent-reconcile (system=$externalSystemId): partner returned ZERO companies — " +
                    "treated as no-data, not all-removed",
            )
            return
        }

        val activeAgencies = agencyRepository.findAllActiveByPrimarySyncProvider(externalSystemId.toLong())
        // Source rows come from DEDICATED queries, never from Agency.agencySources here:
        // findAllActiveByPrimarySyncProvider fetch-joins WITH a filter, so the loaded
        // collection contains ONLY this system's primary row and a dual-primary agency
        // would look single-sourced (and get wrongly deactivated).
        val thisSystemSourceByAgencyId =
            agencySourceRepository
                .findAllByExternalSystemId(externalSystemId)
                .associateBy { it.id?.agencyId }
        val otherSystemPrimaryAgencyIds =
            agencySourceRepository
                .findAllPrimary()
                .filter { it.id?.externalSystemId != externalSystemId }
                .mapNotNull { it.id?.agencyId }
                .toSet()

        val absent =
            activeAgencies.filter { agency ->
                val externalId = thisSystemSourceByAgencyId[agency.id]?.externalId
                if (externalId == null || externalId in presentExternalIds) return@filter false
                // A legacy dual-PRIMARY agency (both systems flag primary) is still served by the
                // other partner — deactivating would hide the other system's live fleet, and the
                // two mirrors would fight over the row. Leave it active and just log.
                if (agency.id in otherSystemPrimaryAgencyIds) {
                    log.warn(
                        "Agency ${agency.id} (${agency.name}) absent from partner system $externalSystemId but " +
                            "still primary in another system — leaving active (dual-primary legacy row)",
                    )
                    return@filter false
                }
                true
            }
        if (absent.isEmpty()) return

        val maxDeactivatable = PartnerWithdrawalGuard.maxWithdrawable(activeAgencies.size)
        if (absent.size > maxDeactivatable) {
            log.warn(
                "Skip agency absent-reconcile (system=$externalSystemId): would deactivate ${absent.size} of " +
                    "${activeAgencies.size} active agencies (over cap $maxDeactivatable) — likely a partial/" +
                    "truncated partner response. Deactivating nothing; will retry on the next complete response.",
            )
            return
        }

        absent.forEach { agency ->
            agency.active = false
            agency.syncDeactivatedBy = externalSystemId
            log.info(
                "Deactivated agency ${agency.id} (${agency.name}) — no longer returned by partner " +
                    "system $externalSystemId",
            )
        }
        agencyRepository.saveAll(absent)
        log.info(
            "Agency absent-reconcile system=$externalSystemId: deactivated ${absent.size} of " +
                "${activeAgencies.size} active agencies",
        )
    }
}
