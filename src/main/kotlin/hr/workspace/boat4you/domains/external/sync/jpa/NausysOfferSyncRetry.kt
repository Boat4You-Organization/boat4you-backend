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
 * One failed (agency, week interval) of the nightly NauSys offer sync, waiting
 * to be re-fetched. See V9_54 and NauSysYachtOfferIntegrationService.drainRetryQueue.
 */
@Entity
@Table(name = "nausys_offer_sync_retry")
open class NausysOfferSyncRetry {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @Column(name = "agency_id", nullable = false)
    open var agencyId: Long? = null

    @NotNull
    @Column(name = "period_from", nullable = false)
    open var periodFrom: LocalDate? = null

    @NotNull
    @Column(name = "period_to", nullable = false)
    open var periodTo: LocalDate? = null

    /** NauSys yacht ids of the reservation-options group, comma separated. */
    @NotNull
    @Column(name = "yacht_external_ids", nullable = false)
    open var yachtExternalIds: String? = null

    @Column(name = "skip_disappearance", nullable = false)
    open var skipDisappearance: Boolean = false

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

    fun yachtExternalIdList(): List<Long> = yachtExternalIds.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
}
