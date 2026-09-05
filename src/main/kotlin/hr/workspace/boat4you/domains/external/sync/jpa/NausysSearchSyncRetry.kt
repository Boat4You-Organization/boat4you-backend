package hr.workspace.boat4you.domains.external.sync.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate

/**
 * One failed non-weekly search warm (Mario, 5.9.2026: search is served from the DB,
 * freshness comes from the scheduler) waiting to be replayed on cusma3. See V9_55 and
 * NauSysYachtOfferIntegrationServiceAsync.drainSearchRetryQueue.
 *
 * The three filter columns hold sorted, comma-separated NauSys external ids; '' means
 * "no filter on that dimension" and is read back as null so the replayed
 * freeYachtsSearch request is identical to the one the API node sent.
 */
@Entity
@Table(name = "nausys_search_sync_retry")
open class NausysSearchSyncRetry {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @Column(name = "period_from", nullable = false)
    open var periodFrom: LocalDate? = null

    @NotNull
    @Column(name = "period_to", nullable = false)
    open var periodTo: LocalDate? = null

    @Column(name = "countries", nullable = false)
    open var countries: String = ""

    @Column(name = "regions", nullable = false)
    open var regions: String = ""

    @Column(name = "locations", nullable = false)
    open var locations: String = ""

    @Column(name = "attempts", nullable = false)
    open var attempts: Int = 0

    @Column(name = "last_error", length = 500)
    open var lastError: String? = null

    @NotNull
    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant? = null

    @NotNull
    @Column(name = "next_attempt_at", nullable = false)
    open var nextAttemptAt: Instant? = null

    fun countryIds(): List<Long>? = parseIds(countries)

    fun regionIds(): List<Long>? = parseIds(regions)

    fun locationIds(): List<Long>? = parseIds(locations)

    companion object {
        /** Inverse of [serializeIds]: '' → null (no filter), never an empty list. */
        fun parseIds(raw: String): List<Long>? = raw.split(',').mapNotNull { it.trim().toLongOrNull() }.takeIf { it.isNotEmpty() }

        /** Sorted so the same filter set always maps to the same row (the UNIQUE key includes these columns). */
        fun serializeIds(ids: List<Long>?): String = ids?.sorted()?.joinToString(",") ?: ""
    }
}
