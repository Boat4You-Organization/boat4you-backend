package hr.workspace.boat4you.domains.external.sync.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

interface NausysSearchSyncRetryRepository : JpaRepository<NausysSearchSyncRetry, Long> {
    /** Oldest due row first (D3: ordered by next_attempt_at, unlike the agency queue's created_at). */
    fun findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
        now: Instant,
        pageable: Pageable,
    ): List<NausysSearchSyncRetry>

    /**
     * Upsert keyed by (from, to, countries, regions, locations): a search that fails
     * again just bumps `attempts` and pushes `next_attempt_at` out. Timestamps are bound
     * from the JVM (not SQL `now()`) so they follow the same Instant↔timestamp
     * convention as the entity reads — mixing the two skews by the JVM/DB zone offset.
     */
        /**
     * Attempt accounting: the INSERT records the failed live warm as attempt 1, so a row gets
     * 1 live attempt + 5 scheduler replays before the drain gives up (MAX_ATTEMPTS = 6). Every
     * further live failure of the same request (same dates + filter) bumps attempts too, so a
     * range that keeps failing on the API node is retired sooner rather than replayed forever.
     */
@Modifying
    @Query(
        nativeQuery = true,
        value = """
        INSERT INTO nausys_search_sync_retry
            (period_from, period_to, countries, regions, locations, attempts, last_error, created_at, next_attempt_at)
        VALUES (:periodFrom, :periodTo, :countries, :regions, :locations, 1, :lastError, :now, :nextAttemptAt)
        ON CONFLICT (period_from, period_to, countries, regions, locations) DO UPDATE SET
            attempts = nausys_search_sync_retry.attempts + 1,
            last_error = EXCLUDED.last_error,
            next_attempt_at = EXCLUDED.next_attempt_at
        """,
    )
    @Suppress("LongParameterList") // mirrors the (interval, three filter columns, cause, timestamps) row shape
    fun upsert(
        @Param("periodFrom") periodFrom: LocalDate,
        @Param("periodTo") periodTo: LocalDate,
        @Param("countries") countries: String,
        @Param("regions") regions: String,
        @Param("locations") locations: String,
        @Param("lastError") lastError: String?,
        @Param("now") now: Instant,
        @Param("nextAttemptAt") nextAttemptAt: Instant,
    ): Int
}
