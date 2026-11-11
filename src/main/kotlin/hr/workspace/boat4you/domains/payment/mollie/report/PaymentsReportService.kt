package hr.workspace.boat4you.domains.payment.mollie.report

import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.payment.mollie.MollieBalanceClient
import hr.workspace.boat4you.domains.payment.mollie.MollieBalanceTransaction
import hr.workspace.boat4you.domains.payment.mollie.MollieProperties
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.CardChargeCalculator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Collects the accounting report of a period: the paid Mollie payments from our two ledgers (booking payments and
 * `/pay` payments) with payer and booking number, and from Mollie's balance the refunds, the transfers to the bank and
 * what each transfer consists of. Read-only, towards the database and towards Mollie.
 *
 * Mollie's part needs the organisation token; without it, or when Mollie does not answer, the report still lists the
 * payments and says that the balance part is missing ([PaymentsReport.ledger] = null).
 */
@Service
class PaymentsReportService(
    private val orders: ReservationCardOrderRepository,
    private val customPayments: CustomPaymentRepository,
    private val reservations: ReservationRepository,
    private val balances: MollieBalanceClient,
    private val properties: MollieProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [from] and [to] are Croatian calendar days, both inclusive. */
    fun build(
        from: LocalDate,
        to: LocalDate,
        now: Instant = Instant.now(),
    ): PaymentsReport {
        val start = from.atStartOfDay(ZONE).toInstant()
        val end = to.plusDays(1).atStartOfDay(ZONE).toInstant()

        val bookingPayments = orders.findReportRowsPaidBetween(ReservationCardOrderStatus.COMPLETED, start, end).mapNotNull { it.toPayment() }
        val linkRows = customPayments.findPaidBetween(CustomPaymentStatus.COMPLETED, start, end)
        val linkParties = linkParties(linkRows)
        val linkPayments = linkRows.mapNotNull { it.toPayment(linkParties) }

        return PaymentsReport(
            from = from,
            to = to,
            generatedAt = now,
            payments = (bookingPayments + linkPayments).sortedBy { it.paidAt },
            ledger = readLedger(start)?.let { (transactions, complete) -> PaymentsReportAssembler.ledger(transactions, start, end, partiesOf(transactions), complete) },
        )
    }

    /**
     * Mollie's balance transactions, oldest first, back to (not including) the last transfer made before [start]: what
     * is older belongs to earlier transfers. The flag is false when [MAX_PAGES] or [READ_BUDGET] ended the reading
     * first (the request must not hold a thread of the API node for minutes when Mollie answers slowly).
     */
    private fun readLedger(start: Instant): Pair<List<MollieBalanceTransaction>, Boolean>? {
        if (!properties.feesEnabled) return null
        return try {
            val newestFirst = ArrayList<MollieBalanceTransaction>()
            var cursor: String? = null
            var pages = 0
            var complete = true
            val started = System.nanoTime()
            while (true) {
                val page = balances.listTransactions(cursor)
                pages++
                val transactions = page.transactions
                val boundary = transactions.indexOfFirst { it.type in PaymentsReportAssembler.PAYOUT_TYPES && it.createdAt?.isBefore(start) == true }
                if (boundary >= 0) {
                    newestFirst += transactions.subList(0, boundary)
                    break
                }
                newestFirst += transactions
                cursor = page.nextFrom.takeIf { transactions.isNotEmpty() } ?: break
                if (pages >= MAX_PAGES || System.nanoTime() - started > READ_BUDGET.toNanos()) {
                    complete = false
                    break
                }
            }
            newestFirst.asReversed() to complete
        } catch (e: Exception) {
            log.warn("Payments report: Mollie balance could not be read ({})", e.javaClass.simpleName)
            null
        }
    }

    /** Our ledgers' rows of the payments the balance transactions name, whatever their status. */
    private fun partiesOf(transactions: List<MollieBalanceTransaction>): Map<String, ReportParty> {
        val ids = transactions.mapNotNull { it.paymentId }.toSet()
        if (ids.isEmpty()) return emptyMap()
        val parties = HashMap<String, ReportParty>()
        ids.chunked(IN_CHUNK).forEach { chunk ->
            orders.findReportRowsByProviderOrderIdIn(chunk).forEach { row -> parties[(row[ORDER] as ReservationCardOrder).providerOrderId] = row.toParty() }
            val linkRows = customPayments.findAllByProviderOrderIdIn(chunk)
            val linkParties = linkParties(linkRows)
            linkRows.forEach { row -> row.providerOrderId?.let { parties[it] = row.toParty(linkParties) } }
        }
        return parties
    }

    /** `reservationId -> (reservation number, client name)` of the reservations the `/pay` payments are tied to. */
    private fun linkParties(rows: List<CustomPayment>): Map<Long, Pair<String?, String?>> {
        val ids = rows.mapNotNull { it.reservationId }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(IN_CHUNK).flatMap { reservations.findReportPartiesByIdIn(it) }.associate { row ->
            (row[0] as Number).toLong() to ((row[1] as String?) to fullName(row[2] as String?, row[3] as String?))
        }
    }

    private fun Array<Any?>.toParty(): ReportParty {
        val order = this[ORDER] as ReservationCardOrder
        return ReportParty(
            kind = ReportPaymentKind.BOOKING,
            payerName = fullName(this[NAME] as String?, this[SURNAME] as String?),
            payerEmail = (this[EMAIL] as String?)?.trim()?.ifEmpty { null },
            reference = (this[RESERVATION_NUMBER] as String?)?.trim()?.ifEmpty { null } ?: order.reference,
            needsReview = order.status == ReservationCardOrderStatus.NEEDS_REVIEW,
        )
    }

    private fun Array<Any?>.toPayment(): ReportPayment? {
        val order = this[ORDER] as ReservationCardOrder
        return ReportPayment(
            paidAt = order.completedAt ?: return null,
            party = toParty(),
            method = order.methodOrCard.name,
            molliePaymentId = order.providerOrderId,
            amountMinor = order.amountMinor,
            baseMinor = CardChargeCalculator.toCentsLong(order.baseAmount),
            surchargeMinor = CardChargeCalculator.toCentsLong(order.surchargeAmount),
            feeMinor = order.providerFeeMinor,
            moneyBackMinor = order.refundedAmountMinor + order.chargedBackAmountMinor,
        )
    }

    private fun CustomPayment.toParty(linked: Map<Long, Pair<String?, String?>>): ReportParty {
        val reservation = reservationId?.let { linked[it] }
        return ReportParty(
            kind = ReportPaymentKind.LINK,
            payerName = reservation?.second,
            payerEmail = payerEmail.trim().ifEmpty { null },
            reference = reference,
            linkedReservation = reservation?.first?.takeIf { !it.equals(reference.trim(), ignoreCase = true) },
            needsReview = status == CustomPaymentStatus.NEEDS_REVIEW,
        )
    }

    private fun CustomPayment.toPayment(linked: Map<Long, Pair<String?, String?>>): ReportPayment? =
        ReportPayment(
            paidAt = completedAt ?: return null,
            party = toParty(linked),
            method = method ?: CustomPayment.METHOD_CARD,
            molliePaymentId = providerOrderId ?: return null,
            amountMinor = amountMinor,
            feeMinor = providerFeeMinor,
            moneyBackMinor = refundedAmountMinor + chargedBackAmountMinor,
        )

    private fun fullName(
        name: String?,
        surname: String?,
    ): String? = listOfNotNull(name, surname).joinToString(" ") { it.trim() }.trim().ifEmpty { null }

    companion object {
        /** The report's calendar: the office and its bookkeeping are in Croatia. */
        val ZONE: ZoneId = ZoneId.of("Europe/Zagreb")

        /** 40 pages of 250 = the last 10 000 movements on the balance. */
        private const val MAX_PAGES = 40

        /** The whole reading of the balance; a page is fetched only while less than this has passed. */
        private val READ_BUDGET: Duration = Duration.ofSeconds(60)
        private const val IN_CHUNK = 500
        private const val ORDER = 0
        private const val NAME = 1
        private const val SURNAME = 2
        private const val EMAIL = 3
        private const val RESERVATION_NUMBER = 4
    }
}
