package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.sync.jpa.NausysSearchSyncRetry
import hr.workspace.boat4you.domains.external.sync.jpa.NausysSearchSyncRetryRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Durable retry queue for failed on-demand NauSys search warms (table
 * `nausys_search_sync_retry`, V9_55). Mario, 5.9.2026: the API node (cusma2) must not
 * retry NauSys itself — a non-weekly search whose live freeYachtsSearch fails is parked
 * here and replayed by the scheduler (cusma3) every 15 minutes.
 *
 * Every write runs in its own transaction: the API-node warm has no ambient transaction
 * and the scheduler drain must not be able to roll a row back (or be rolled back) with
 * the partner call around it. Mirrors [NausysOfferSyncRetryQueue].
 */
@Service
class NausysSearchSyncRetryQueue(
    private val repository: NausysSearchSyncRetryRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        const val MAX_ATTEMPTS = 6
        val RETRY_STEP: Duration = Duration.ofMinutes(15)
        const val MAX_ROWS_PER_DRAIN = 25
        private const val MAX_ERROR_LENGTH = 500
    }

    @Suppress("LongParameterList") // mirrors the (interval, three NauSys filter lists, cause) row shape
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun enqueue(
        periodFrom: LocalDate,
        periodTo: LocalDate,
        countries: List<Long>?,
        regions: List<Long>?,
        locations: List<Long>?,
        error: Throwable,
    ) {
        val now = Instant.now()
        repository.upsert(
            periodFrom,
            periodTo,
            NausysSearchSyncRetry.serializeIds(countries),
            NausysSearchSyncRetry.serializeIds(regions),
            NausysSearchSyncRetry.serializeIds(locations),
            describe(error),
            now,
            now.plus(RETRY_STEP),
        )
    }

    @Transactional(readOnly = true)
    fun due(maxRows: Int = MAX_ROWS_PER_DRAIN): List<NausysSearchSyncRetry> =
        repository.findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(Instant.now(), PageRequest.of(0, maxRows))

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSuccess(row: NausysSearchSyncRetry) {
        row.id?.let { repository.deleteById(it) }
    }

    /**
     * Bumps the row (next attempt in 15 min × attempts); returns true when it was given
     * up (deleted) after [MAX_ATTEMPTS]. Give-up is WARN, not ERROR: the search itself
     * was served from the DB all along, only its refresh was lost (D3, 5.9.2026).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailure(
        row: NausysSearchSyncRetry,
        error: Throwable,
    ): Boolean {
        val attempts = row.attempts + 1
        if (attempts >= MAX_ATTEMPTS) {
            log.warn(
                "NauSYS search retry GIVING UP ${row.periodFrom}→${row.periodTo} countries=[${row.countries}] regions=[${row.regions}] " +
                    "locations=[${row.locations}] after $attempts attempts: ${describe(error)}",
            )
            row.id?.let { repository.deleteById(it) }
            return true
        }
        row.attempts = attempts
        row.lastError = describe(error)
        row.nextAttemptAt = Instant.now().plus(RETRY_STEP.multipliedBy(attempts.toLong()))
        repository.save(row)
        return false
    }

    private fun describe(error: Throwable): String = error.toString().take(MAX_ERROR_LENGTH)
}
