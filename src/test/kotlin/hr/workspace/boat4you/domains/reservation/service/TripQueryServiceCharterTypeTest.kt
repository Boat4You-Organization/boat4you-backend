package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * The trip hub (/trip/{token}) gets the reservation's charter type as my-bookings and the admin detail show it
 * (ReservationCharterRules.displayType): a gulet is CREWED (Mario 21.10.2026), every other boat the booked offer's product.
 * The web shows the crew count only for a crewed type.
 */
class TripQueryServiceCharterTypeTest {
    /** TripYachtDto.charterType for a reservation on this boat, booked on an offer of this product. */
    private fun tripCharterType(
        vessel: VesselType?,
        modelName: String,
        product: CharterType?,
        withOffer: Boolean = true,
    ): CharterType? {
        val reservation =
            Reservation().apply {
                id = 7
                reservationNumber = "100700/2027"
                dateFrom = LocalDateTime.of(2027, 7, 3, 17, 0)
                dateTo = LocalDateTime.of(2027, 7, 10, 9, 0)
                reservationFlow =
                    ReservationFlow().apply {
                        yacht =
                            Yacht().apply {
                                id = 18886
                                name = "Sylvia R"
                                entryType = EntryType.EXTERNAL
                                vesselType = vessel
                                model = Model().apply { name = modelName }
                            }
                        offer = if (withOffer) Offer().apply { this.product = product } else null
                    }
            }
        val reservations = mock(ReservationRepository::class.java)
        `when`(reservations.findByTripToken("TOKEN")).thenReturn(reservation)
        val service =
            TripQueryService(
                reservationRepository = reservations,
                reservationDocumentService = mock(ReservationDocumentService::class.java),
                tripPushService = mock(TripPushService::class.java),
                tripCrewService = mock(TripCrewService::class.java),
                capacityMapper = YachtCapacityMapper(CapacityFixtures.sanitizer()),
            )
        return service.getTrip("TOKEN")!!.yacht.charterType
    }

    @Test
    fun `a gulet is CREWED - tagged BAREBOAT, by model name, without an offer`() {
        assertEquals(CharterType.CREWED, tripCharterType(VesselType.GULET, "Custom", CharterType.BAREBOAT))
        assertEquals(CharterType.CREWED, tripCharterType(VesselType.MOTOR_YACHT, "Gulet", CharterType.UNKNOWN))
        assertEquals(CharterType.CREWED, tripCharterType(VesselType.GULET, "Custom", null, withOffer = false))
        // a gulet's own crewed products stay
        assertEquals(CharterType.ALL_INCLUSIVE, tripCharterType(VesselType.GULET, "Custom", CharterType.ALL_INCLUSIVE))
    }

    @Test
    fun `every other boat - the booked offer's product`() {
        assertEquals(CharterType.CREWED, tripCharterType(VesselType.CATAMARAN, "Lagoon 42", CharterType.CREWED))
        assertEquals(CharterType.CRUISE, tripCharterType(VesselType.MOTOR_YACHT, "Sunseeker 76", CharterType.CRUISE))
        assertEquals(CharterType.BAREBOAT, tripCharterType(VesselType.SAILING_YACHT, "Oceanis 46.1", CharterType.BAREBOAT))
        assertEquals(null, tripCharterType(VesselType.SAILING_YACHT, "Oceanis 46.1", null))
        assertEquals(null, tripCharterType(VesselType.SAILING_YACHT, "Oceanis 46.1", null, withOffer = false))
    }
}
