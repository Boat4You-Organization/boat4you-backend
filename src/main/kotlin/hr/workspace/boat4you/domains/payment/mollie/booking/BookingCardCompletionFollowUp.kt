package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.CardOrderPromoteState
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.reservation.enums.PaymentType
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.ReservationEmailService
import hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService
import hr.workspace.boat4you.domains.reservation.service.ReservationMutationService
import hr.workspace.boat4you.domains.voucher.service.VoucherService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * What runs AFTER [ReservationCardOrderTx.applyCompleted] committed (plan 2A.10 M4), outside any transaction:
 * - an alert reason (NEEDS_REVIEW: mismatch or double payment) -> office mail;
 * - THIS call completed the row -> "Card payment received" to the office + the payment confirmation to the client
 *   ([BookingCardPaymentMailer], Mario 10.11.2026), whatever the kind of payment;
 * - the first payment of a booking ([CompletionResult.promote]) when THIS call completed the row -> the same booking
 *   confirmation as always (partner confirm, reservation confirmed, customer mail with PaymentType.CARD, voucher),
 *   never for a reservation that is CANCELLED meanwhile. The payment stays recorded if the confirmation fails: the
 *   row gets promote_state FAILED and the office an alert (confirm by hand).
 */
@Component
class BookingCardCompletionFollowUp(
    private val orders: ReservationCardOrderRepository,
    private val orderTx: ReservationCardOrderTx,
    private val reservationRepository: ReservationRepository,
    private val reservationIntegrationService: ReservationIntegrationService,
    private val reservationMutationService: ReservationMutationService,
    private val reservationEmailService: ReservationEmailService,
    private val voucherService: VoucherService,
    private val alertMailer: BookingCardAlertMailer,
    private val paymentMailer: BookingCardPaymentMailer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun afterCompletion(
        ledgerId: Long,
        result: CompletionResult,
        cardLabel: String? = null,
    ) {
        result.alert?.let { reason ->
            orders.findById(ledgerId).orElse(null)?.let { alertMailer.alert(it, "Card payment needs review", reason) }
        }
        if (!result.completedNow) return
        // Once per payment: "Card payment received" to the office and the payment confirmation to the client.
        runCatching { orderTx.paymentNotice(ledgerId)?.let { paymentMailer.paymentRecorded(it, cardLabel) } }
            .onFailure { log.error("Mollie booking ledger {}: payment e-mails failed", ledgerId, it) }
        if (!result.promote) return
        val reservationId = result.reservationId ?: return
        val reservation = reservationRepository.findById(reservationId).orElse(null)
        if (reservation == null || reservation.sysStatus == ReservationStatus.CANCELLED) {
            log.warn("Mollie booking ledger {}: reservation {} is gone or CANCELLED - paid but NOT confirmed", ledgerId, reservationId)
            orderTx.markPromote(ledgerId, CardOrderPromoteState.FAILED)
            orders.findById(ledgerId).orElse(null)?.let {
                alertMailer.alert(it, "Card payment for a cancelled booking", "Reservation $reservationId is cancelled or deleted; refund or rebook")
            }
            return
        }
        runCatching {
            val externalReservation = reservationIntegrationService.confirmExternalReservation(reservationId)
            val reservationResponse = reservationMutationService.confirmReservation(reservationId, externalReservation)
            reservationEmailService.sendConfirmationForReserved(reservationResponse, PaymentType.CARD)
        }.onSuccess {
            orderTx.markPromote(ledgerId, CardOrderPromoteState.DONE)
            runCatching { voucherService.issueForConfirmedReservation(reservationId) }
                .onFailure { log.error("Voucher issuance failed for reservation {}", reservationId, it) }
        }.onFailure {
            log.error("Mollie booking ledger {}: reservation {} paid but the booking confirmation failed", ledgerId, reservationId, it)
            orderTx.markPromote(ledgerId, CardOrderPromoteState.FAILED)
            orders.findById(ledgerId).orElse(null)?.let { row ->
                alertMailer.alert(row, "Card payment received, booking NOT confirmed", "Partner / booking confirmation failed: ${it.javaClass.simpleName}")
            }
        }
    }
}
