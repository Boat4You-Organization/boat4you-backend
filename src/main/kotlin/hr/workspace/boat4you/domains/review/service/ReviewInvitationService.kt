package hr.workspace.boat4you.domains.review.service

import hr.workspace.boat4you.domains.review.ReviewKind
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

data class ReviewSweepResult(
    val bookingSent: Int,
    val yachtSent: Int,
    val failed: Int,
)

/**
 * Sends review request e-mails, at most once per reservation and kind.
 *
 * - BOOKING: right after the first payment (ReviewPaymentListener, after the payment transaction commits), with the
 *   daily sweep as a safety net for a lost event (node restart between commit and send).
 * - YACHT: the daily sweep, 3 days after the charter ends.
 *
 * Every send runs in its own transaction: claim the (reservation, kind) row -> render + queue the mail -> commit.
 * The mail leaves only after the commit (EmailService defers the SMTP submit), and a second sender loses the claim,
 * so a reservation is never mailed twice for the same kind. Gated by application.reviews.enabled.
 */
@Service
class ReviewInvitationService(
    private val dao: ReviewDao,
    private val mailer: ReviewInvitationMailer,
    transactionManager: PlatformTransactionManager,
    @Value("\${server.host-public}") private val serverHostPublic: String,
    @Value("\${application.reviews.enabled:false}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val tx = TransactionTemplate(transactionManager)

    companion object {
        /** Upper bound per kind and run — a burst (e.g. first run after an outage) is spread over several days. */
        const val MAX_PER_RUN = 200
    }

    /** Payment path: sends the BOOKING request if this reservation qualifies now. Never throws to the caller. */
    fun sendBookingInvitationIfEligible(reservationId: Long): Boolean {
        if (!enabled) return false
        return runCatching { invite(reservationId, ReviewKind.BOOKING) }
            .onFailure { log.error("Booking review request failed for reservation {}", reservationId, it) }
            .getOrDefault(false)
    }

    /** Daily run (ReviewInvitationJob, scheduler node): YACHT requests + BOOKING catch-up. */
    fun runDailySweep(): ReviewSweepResult {
        if (!enabled) {
            log.info("Review requests disabled (application.reviews.enabled=false) — sweep skipped")
            return ReviewSweepResult(0, 0, 0)
        }
        var failed = 0
        fun sendAll(
            ids: List<Long>,
            kind: ReviewKind,
        ): Int =
            ids.count { id ->
                runCatching { invite(id, kind) }
                    .onFailure {
                        failed++
                        log.error("{} review request failed for reservation {}", kind, id, it)
                    }.getOrDefault(false)
            }

        val booking = sendAll(dao.bookingCandidateIds(MAX_PER_RUN), ReviewKind.BOOKING)
        val yacht = sendAll(dao.yachtCandidateIds(MAX_PER_RUN), ReviewKind.YACHT)
        val result = ReviewSweepResult(booking, yacht, failed)
        log.info("Review request sweep: {}", result)
        return result
    }

    /**
     * Claim + send in one transaction; false when the reservation does not qualify (any more), another sender already
     * claimed it, or the data is incomplete. BOOKING eligibility is re-checked right here, per reservation: the
     * sweep's candidate list is computed up front, and the yacht-swap chain rule depends on earlier claims of the
     * same run.
     */
    private fun invite(
        reservationId: Long,
        kind: ReviewKind,
    ): Boolean =
        tx.execute {
            if (kind == ReviewKind.BOOKING && dao.bookingCandidateIds(1, reservationId).isEmpty()) {
                return@execute false
            }
            val context = dao.loadReservationContext(reservationId)
            if (context == null || context.flowEmail.isNullOrBlank()) {
                log.warn("{} review request skipped for reservation {}: no recipient", kind, reservationId)
                return@execute false
            }
            val locale = ReviewLinks.normalizeLocale(context.userLanguage) ?: ReviewLinks.DEFAULT_LOCALE
            val token = ReviewTokens.generate()
            val claimed =
                dao.claimRequest(reservationId, kind, ReviewTokens.hash(token), locale, ReviewTokens.VALIDITY.toDays())
            if (!claimed) return@execute false
            mailer.send(kind, context, locale, ReviewLinks.reviewUrl(serverHostPublic, locale, token))
            log.info("{} review request sent for reservation {} ({})", kind, reservationId, locale)
            true
        } ?: false
}
