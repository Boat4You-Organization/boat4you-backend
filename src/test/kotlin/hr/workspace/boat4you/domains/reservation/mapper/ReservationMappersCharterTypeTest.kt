package hr.workspace.boat4you.domains.reservation.mapper

import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.BAREBOAT
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CREWED
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.mapper.YachtExtrasMapper
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationView
import hr.workspace.boat4you.domains.reservation.service.ReservationDocumentService
import hr.workspace.boat4you.domains.reservation.service.ReservationPaymentPhasesService
import hr.workspace.boat4you.domains.voucher.jpa.VoucherRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * my-bookings (MyReservationDetailsDto) and the admin reservation detail (ReservationViewDetailsDto) take the charter type
 * from the offer (reservation_view.charter_type), except a gulet: always CREWED (Mario 21.10.2026, ReservationCharterRules).
 */
class ReservationMappersCharterTypeTest {
    private val exchangeRates = mock(ExchangeRateCalculationService::class.java)
    private val mappers =
        ReservationMappers(
            extrasMapper = YachtExtrasMapper(exchangeRates),
            paymentPhasesService = mock(ReservationPaymentPhasesService::class.java),
            exchangeRateCalculationService = exchangeRates,
            reservationDocumentService = mock(ReservationDocumentService::class.java),
            voucherRepository = mock(VoucherRepository::class.java),
            capacityMapper = YachtCapacityMapper(CapacityFixtures.sanitizer()),
        )

    private fun yacht(
        vessel: VesselType,
        modelName: String,
    ) = Yacht().apply {
        id = 18886
        name = "Sylvia R"
        entryType = EntryType.EXTERNAL
        vesselType = vessel
        model = Model().apply { name = modelName }
    }

    /** reservation_view is read-only (protected constructor and setters): a subclass fills one row. */
    private class View(
        model: String,
        offerType: CharterType?,
    ) : ReservationView() {
        init {
            reservationId = 27
            reservationFlowId = 70
            reservationStatus = OfferStatus.RESERVED
            reservationSysStatus = ReservationStatus.RESERVATION
            reservationCreatedAt = LocalDateTime.of(2026, 10, 1, 10, 0)
            reservationDateFrom = LocalDateTime.of(2027, 7, 3, 17, 0)
            reservationDateTo = LocalDateTime.of(2027, 7, 10, 9, 0)
            reservationFlowEmail = "charterer@example.com"
            createdByEmail = "charterer@example.com"
            yachtId = 18886
            yachtName = "Sylvia R"
            modelName = model
            locationFromCountry = "HR"
            locationToName = "Split"
            locationToCountry = "HR"
            calculatedTotalPrice = BigDecimal("9800.00")
            agencyId = 5
            agencyName = "Operator"
            agencyEmail = "operator@example.com"
            agencySourceExternalSystemId = 1
            charterType = offerType
        }
    }

    private fun myBookings(
        yacht: Yacht,
        offerType: CharterType?,
    ) = mappers.toMyReservationDetailsResponse(View(yacht.model!!.name!!, offerType), emptyList(), yacht, LanguageEnum.EN, CurrencyEnum.EUR).charterType

    private fun admin(
        yacht: Yacht,
        offerType: CharterType?,
    ) = mappers.toDetailsDto(View(yacht.model!!.name!!, offerType), yacht, emptyList(), CurrencyEnum.EUR, LanguageEnum.EN).charterType

    @Test
    fun `a gulet tagged BAREBOAT reads CREWED in my-bookings and in admin`() {
        val gulet = yacht(VesselType.GULET, "Custom")
        assertEquals(CREWED, myBookings(gulet, BAREBOAT))
        assertEquals(CREWED, admin(gulet, BAREBOAT))
        // b4y Gallant 11771: a MOTOR_YACHT of model "Gulet"
        val byModel = yacht(VesselType.MOTOR_YACHT, "Gulet")
        assertEquals(CREWED, myBookings(byModel, BAREBOAT))
        assertEquals(CREWED, admin(byModel, null))
    }

    @Test
    fun `every other boat keeps the offer's type`() {
        val sailing = yacht(VesselType.SAILING_YACHT, "Oceanis 46.1")
        assertEquals(BAREBOAT, myBookings(sailing, BAREBOAT))
        assertEquals(BAREBOAT, admin(sailing, BAREBOAT))
        assertEquals(CREWED, myBookings(sailing, CREWED))
        assertEquals(CREWED, admin(sailing, CREWED))
        assertEquals(null, admin(sailing, null))
    }
}
