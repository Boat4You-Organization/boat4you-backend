package hr.workspace.boat4you.domains.external.service

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

@Service
@Transactional(readOnly = true)
class ServiceCallCacheService(
    private val serviceCallCacheRepository: ServiceCallCacheRepository,
) {
    fun shouldCallOffer(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): Boolean {
        val hash = calculateHash(yachtId, dateFrom, dateTo)
        val maxCreatedAt = serviceCallCacheRepository.findByMethodAndHashCode(MethodCacheEnum.OFFER, hash)
        if (maxCreatedAt == null) {
            return true
        }
        if (maxCreatedAt < Instant.now().minus(1, ChronoUnit.HOURS)) {
            return true
        }
        return false
    }

    fun shouldCallYachtSearch(
        startDate: LocalDate,
        endDate: LocalDate,
        locations: List<String>,
    ): Boolean {
        val hash = createSyncYachtOffersHashSorted(startDate, endDate, locations)
        val maxCreatedAt = serviceCallCacheRepository.findByMethodAndHashCode(MethodCacheEnum.YACHT_SEARCH, hash)
        if (maxCreatedAt == null) {
            return true
        }
        if (maxCreatedAt < Instant.now().minus(1, ChronoUnit.HOURS)) {
            return true
        }
        return false
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveOfferSync(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ) {
        val serviceCallCache = ServiceCallCache()
        serviceCallCache.method = MethodCacheEnum.OFFER
        serviceCallCache.hashCode = calculateHash(yachtId, dateFrom, dateTo)
        serviceCallCache.createdAt = Instant.now()
        serviceCallCacheRepository.save(serviceCallCache)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveYachtSearch(
        startDate: LocalDate,
        endDate: LocalDate,
        locations: List<String>,
    ) {
        val serviceCallCache = ServiceCallCache()
        serviceCallCache.method = MethodCacheEnum.OFFER
        serviceCallCache.hashCode = createSyncYachtOffersHashSorted(startDate, endDate, locations)
        serviceCallCache.createdAt = Instant.now()
        serviceCallCacheRepository.save(serviceCallCache)
    }

    private fun calculateHash(
        id: Long,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
    ): Long {
        return Objects.hash(id, dateFrom, dateTo).toLong()
    }

    fun createSyncYachtOffersHashSorted(
        startDate: LocalDate,
        endDate: LocalDate,
        locations: List<String>,
    ): Long {
        val sortedLocations = locations.sorted()
        return Objects.hash(startDate, endDate, sortedLocations).toLong()
    }

    fun shouldRunScheduledSync(method: MethodCacheEnum): Boolean {
        val maxCreatedAt =
            serviceCallCacheRepository.findByMethodAndHashCode(method, 0L)
        if (maxCreatedAt == null) {
            return true
        }
        if (maxCreatedAt < Instant.now().minus(24, ChronoUnit.HOURS)) {
            return true
        }
        return false
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveScheduledSync(method: MethodCacheEnum) {
        val serviceCallCache = ServiceCallCache()
        serviceCallCache.method = method
        serviceCallCache.hashCode = 0
        serviceCallCache.createdAt = Instant.now()
        serviceCallCacheRepository.save(serviceCallCache)
    }
}
