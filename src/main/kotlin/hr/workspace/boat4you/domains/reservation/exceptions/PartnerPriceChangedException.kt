package hr.workspace.boat4you.domains.reservation.exceptions

import java.math.BigDecimal

/**
 * WS-C2 (`PARTNER_PRICE_GUARD=ENFORCE`): the price the partner put on the option just created is above our offer
 * snapshot by more than the tolerance. Thrown between createOption and createReservation, before any Reservation
 * exists; the booking orchestration releases the partner option, abandons the flow and alerts the admins, and the
 * customer gets 409 / 3007. Nothing the customer saw or paid is changed.
 */
class PartnerPriceChangedException(
    val reservationFlowId: Long,
    val offerId: Long?,
    val partnerNet: BigDecimal,
    val snapshotNet: BigDecimal,
    val currency: String,
) : RuntimeException(
        "Partner price for flow $reservationFlowId (offer $offerId) is $partnerNet $currency, our snapshot $snapshotNet",
    )
