package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.sync.jpa.NausysOfferSyncRetry
import hr.workspace.boat4you.domains.external.sync.jpa.NausysOfferSyncRetryRepository
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
 * Durable per-interval retry queue for the NauSys offer sync (table
 * `nausys_offer_sync_retry`, V9_54). Every write runs in its own transaction
 * so a failure in the surrounding sync loop can neither roll it back nor be
 * rolled back by it.
 */
@Service
class NausysOfferSyncRetryQueue(
    private val repository: NausysOfferSyncRetryRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        const val MAX_ATTEMPTS = 6
        val RETRY_STEP: Duration = Duration.ofMinutes(15)
        private const val MAX_ERROR_LENGTH = 500
    }

    @Suppress("LongParameterList") // mirrors the (agency, interval, ids, flag, cause) row shape
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun enqueue(
        agencyId: Long,
        periodFrom: LocalDate,
        periodTo: LocalDate,
        yachtExternalIds: List<Long>,
        skipDisappearance: Boolean,
        error: Throwable,
    ) {
        val now = Instant.now()
        repository.upsert(
            agencyId,
            periodFrom,
            periodTo,
            yachtExternalIds.joinToString(","),
            skipDisappearance,
            describe(error),
            now,
            now.plus(RETRY_STEP),
        )
    }

    @Transactional(readOnly = true)
    fun due(maxRows: Int): List<NausysOfferSyncRetry> =
        repository.findByNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Instant.now(), PageRequest.of(0, maxRows))

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSuccess(row: NausysOfferSyncRetry) {
        row.id?.let { repository.deleteById(it) }
    }

    /** Bumps the row; returns true when it was given up (deleted) after [MAX_ATTEMPTS]. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailure(
        row: NausysOfferSyncRetry,
        error: Throwable,
    ): Boolean {
        val attempts = row.attempts + 1
        if (attempts >= MAX_ATTEMPTS) {
            log.error(
                "NauSYS offer retry GIVING UP agency=${row.agencyId} ${row.periodFrom}→${row.periodTo} after $attempts attempts: ${describe(error)}",
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

    private fun describe(error: Throwable): String = (error.toString()).take(MAX_ERROR_LENGTH)
}
