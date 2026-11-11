package hr.workspace.boat4you.domains.payment.mollie

import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Fills `provider_fee_minor` (Mollie's fee per payment) of the paid booking payments and `/pay` payments that do not
 * have it yet. Mollie books every payment on the primary balance as a transaction whose `deductions` is the fee; the
 * sync reads those transactions newest first, back to the oldest payment still waiting, and stores what it finds.
 *
 * Read-only towards Mollie. Without the organisation token ([MollieProperties.feesEnabled]) it does nothing; a payment
 * Mollie has booked without `deductions` (fees invoiced separately) stays NULL and is asked again while it is younger
 * than [LOOKBACK].
 */
@Service
class MollieFeeSyncService(
    private val balances: MollieBalanceClient,
    private val orders: ReservationCardOrderRepository,
    private val customPayments: CustomPaymentRepository,
    private val properties: MollieProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Number of payments whose fee was stored. */
    fun sync(now: Instant = Instant.now()): Int {
        if (!properties.feesEnabled) return 0
        val since = now.minus(LOOKBACK)
        val waiting = HashMap<String, Instant>()
        orders.findFeeCandidates(ReservationCardOrderStatus.COMPLETED, since).forEach { waiting[it[0] as String] = it[1] as Instant }
        customPayments.findFeeCandidates(CustomPaymentStatus.COMPLETED, since).forEach { waiting[it[0] as String] = it[1] as Instant }
        if (waiting.isEmpty()) return 0

        // A payment's balance transaction is never older than the payment itself.
        val stopBefore = waiting.values.min().minus(CLOCK_MARGIN)
        val fees = HashMap<String, Long>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = balances.listTransactions(cursor)
            pages++
            val transactions = page.transactions
            transactions.forEach { transaction ->
                val paymentId = transaction.paymentId
                val fee = transaction.feeMinorOrNull()
                if (paymentId != null && fee != null && paymentId in waiting) fees.merge(paymentId, fee, Long::plus)
            }
            val oldest = transactions.mapNotNull { it.createdAt }.minOrNull()
            cursor = page.nextFrom.takeIf { transactions.isNotEmpty() && (oldest == null || !oldest.isBefore(stopBefore)) }
        } while (cursor != null && pages < MAX_PAGES)

        var stored = 0
        fees.forEach { (paymentId, fee) ->
            stored += orders.storeProviderFee(paymentId, fee) + customPayments.storeProviderFee(paymentId, fee)
        }
        log.info("Mollie fees: {} of {} waiting payments filled ({} page(s) read)", stored, waiting.size, pages)
        return stored
    }

    /** The fee Mollie deducted on this movement, in cents; `null` when the transaction is not a payment or has none. */
    private fun MollieBalanceTransaction.feeMinorOrNull(): Long? {
        if (type !in PAYMENT_TYPES) return null
        val amount = deductions?.takeIf { it.currency == EUR } ?: return null
        return amount.toMinorOrNull()?.let { abs(it) }
    }

    private companion object {
        const val EUR = "EUR"
        const val MAX_PAGES = 40

        /** `payment` = a paid payment; `capture` = the capture of an authorised one (Klarna). */
        val PAYMENT_TYPES = setOf("payment", "capture")

        /** How long a paid payment without a known fee keeps being asked about. */
        val LOOKBACK: Duration = Duration.ofDays(30)
        val CLOCK_MARGIN: Duration = Duration.ofHours(1)
    }
}
