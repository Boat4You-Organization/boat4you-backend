package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodKey
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.exceptions.OfferNotBookableException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * WS-C1 follow-up of a 409 / 3006 rejection, shared by the guest and the signed-in booking endpoints.
 *
 * The booking controllers skip the admin alert for every `hr.workspace.boat4you.*` exception (ordinary rejections), so
 * without this a customer who tried to book a week we had shown as bookable left no trace. Here the admins get the
 * usual booking-failure mail, and exactly that period is queued for a partner re-check (`partner_period_check`,
 * priority 3) so the reverifiers find out whether the week is really gone. Best-effort: nothing here may change the
 * HTTP outcome.
 */
@Component
class OfferNotBookableFollowUp(
    private val bookingFailureAlertService: BookingFailureAlertService,
    private val partnerPeriodCheckStore: PartnerPeriodCheckStore,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun handle(
        request: CreateReservationDto,
        rejection: OfferNotBookableException,
    ) {
        runCatching { bookingFailureAlertService.alertRejectedRequest(request, rejection) }
            .onFailure { log.error("Booking failure alert failed for offer {}", request.offerId, it) }
        runCatching { queueRecheck(rejection) }
            .onFailure { log.warn("Period re-check not queued for offer {}", rejection.offerId, it) }
    }

    private fun queueRecheck(rejection: OfferNotBookableException) {
        val system = rejection.externalSystem?.takeIf { it != ExternalSystemEnum.UNKNOWN }
        val period =
            rejection.yachtId?.let { y -> rejection.dateFrom?.let { f -> rejection.dateTo?.let { t -> PeriodKey(y, f, t) } } }
        if (system == null || period == null) {
            log.info("Offer {} not bookable; no partner period to re-check", rejection.offerId)
        } else {
            partnerPeriodCheckStore.upsertPending(system, period.yachtId, period.dateFrom, period.dateTo, PeriodCheckPriority.ON_DEMAND_OR_BOOKING)
            log.info("Offer {} not bookable; {} period {} queued for a re-check", rejection.offerId, system, period)
        }
    }
}
