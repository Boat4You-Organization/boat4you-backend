package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.sync.jpa.ServiceCallCache
import hr.workspace.boat4you.domains.external.sync.jpa.ServiceCallCacheRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Objects

/**
 * Per-partner 3 h search-warm markers, used only while `NAUSYS_WARM_LONG_RANGES=ON` (DESIGN.md B3). With the switch
 * OFF the search warm keeps the one combined marker of [ServiceCallCacheService.shouldCallYachtSearch].
 *
 * Each partner has its own marker, so a range only one partner warms (a 14-night Saturday warms NauSys, not MMK)
 * never suppresses the other, and one partner's failure never re-warms the other. Stored as ordinary YACHT_SEARCH
 * rows of `service_call_cache` whose hash also carries the partner name: no new enum value and no DDL, so an older
 * jar reading the table is unaffected. Same TTL as the combined marker.
 */
@Service
class PartnerSearchWarmMarkers(
    private val serviceCallCacheRepository: ServiceCallCacheRepository,
) {
    /** True when [partner] has no marker for this search younger than the 3 h TTL. */
    @Transactional(readOnly = true)
    fun isDue(
        partner: ExternalSystemEnum,
        startDate: LocalDate,
        endDate: LocalDate,
        locations: List<String>,
    ): Boolean {
        val newest = serviceCallCacheRepository.findByMethodAndHashCode(MethodCacheEnum.YACHT_SEARCH, hash(partner, startDate, endDate, locations))
        return newest == null || newest < Instant.now().minus(ServiceCallCacheService.YACHT_SEARCH_WARM_TTL_HOURS, ChronoUnit.HOURS)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun save(
        partner: ExternalSystemEnum,
        startDate: LocalDate,
        endDate: LocalDate,
        locations: List<String>,
    ) {
        val marker = ServiceCallCache()
        marker.method = MethodCacheEnum.YACHT_SEARCH
        marker.hashCode = hash(partner, startDate, endDate, locations)
        marker.createdAt = Instant.now()
        serviceCallCacheRepository.save(marker)
    }

    companion object {
        /** Like [ServiceCallCacheService.createSyncYachtOffersHashSorted], with the partner name as the first component. */
        fun hash(
            partner: ExternalSystemEnum,
            startDate: LocalDate,
            endDate: LocalDate,
            locations: List<String>,
        ): Long = Objects.hash(partner.name, startDate, endDate, locations.sorted()).toLong()
    }
}
