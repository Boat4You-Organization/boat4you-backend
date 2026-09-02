package hr.workspace.boat4you.domains.external.sync.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

interface NausysOfferSyncRetryRepository : JpaRepository<NausysOfferSyncRetry, Long> {
    fun findByNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
        now: Instant,
        pageable: Pageable,
    ): List<NausysOfferSyncRetry>

    /**
     * Upsert keyed by (agency, from, to): a week that fails again just bumps
     * `attempts` and pushes `next_attempt_at` out. Timestamps are bound from the
     * JVM (not SQL `now()`) so they follow the same Instant↔timestamp convention
     * as the entity reads — mixing the two skews by the JVM/DB zone offset.
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        INSERT INTO nausys_offer_sync_retry
            (agency_id, period_from, period_to, yacht_external_ids, skip_disappearance, attempts, last_error, created_at, next_attempt_at)
        VALUES (:agencyId, :periodFrom, :periodTo, :yachtExternalIds, :skipDisappearance, 1, :lastError, :now, :nextAttemptAt)
        ON CONFLICT (agency_id, period_from, period_to) DO UPDATE SET
            attempts = nausys_offer_sync_retry.attempts + 1,
            yacht_external_ids = EXCLUDED.yacht_external_ids,
            skip_disappearance = EXCLUDED.skip_disappearance,
            last_error = EXCLUDED.last_error,
            next_attempt_at = EXCLUDED.next_attempt_at
        """,
    )
    fun upsert(
        @Param("agencyId") agencyId: Long,
        @Param("periodFrom") periodFrom: LocalDate,
        @Param("periodTo") periodTo: LocalDate,
        @Param("yachtExternalIds") yachtExternalIds: String,
        @Param("skipDisappearance") skipDisappearance: Boolean,
        @Param("lastError") lastError: String?,
        @Param("now") now: Instant,
        @Param("nextAttemptAt") nextAttemptAt: Instant,
    ): Int
}
