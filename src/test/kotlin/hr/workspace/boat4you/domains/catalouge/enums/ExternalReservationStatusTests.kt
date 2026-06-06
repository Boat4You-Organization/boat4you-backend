package hr.workspace.boat4you.domains.catalouge.enums

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ExternalReservationStatusTests {
    @Test
    fun `fromMmkValue maps every MMK status code to the correct busy state`() {
        // Blocking
        assertEquals(ExternalReservationStatus.RESERVATION, ExternalReservationStatus.fromMmkValue(1L))
        assertEquals(ExternalReservationStatus.SERVICE, ExternalReservationStatus.fromMmkValue(4L))
        assertEquals(ExternalReservationStatus.SERVICE, ExternalReservationStatus.fromMmkValue(6L)) // owner week
        assertEquals(ExternalReservationStatus.SERVICE, ExternalReservationStatus.fromMmkValue(10L)) // regatta
        assertEquals(ExternalReservationStatus.SERVICE, ExternalReservationStatus.fromMmkValue(11L)) // sleep aboard
        // Soft hold (visible, inquiry-only)
        assertEquals(ExternalReservationStatus.OPTION, ExternalReservationStatus.fromMmkValue(2L))
        assertEquals(ExternalReservationStatus.OPTION, ExternalReservationStatus.fromMmkValue(9L)) // option on waiting
        // Available -> FREE (no-op)
        assertEquals(ExternalReservationStatus.FREE, ExternalReservationStatus.fromMmkValue(0L))
        assertEquals(ExternalReservationStatus.FREE, ExternalReservationStatus.fromMmkValue(3L)) // option expired
        assertEquals(ExternalReservationStatus.FREE, ExternalReservationStatus.fromMmkValue(5L)) // cancelled
        assertEquals(ExternalReservationStatus.FREE, ExternalReservationStatus.fromMmkValue(7L)) // offer sent
        assertEquals(ExternalReservationStatus.FREE, ExternalReservationStatus.fromMmkValue(8L)) // custom internal
        // Unmapped / null
        assertEquals(ExternalReservationStatus.UNKNOWN, ExternalReservationStatus.fromMmkValue(null))
        assertEquals(ExternalReservationStatus.UNKNOWN, ExternalReservationStatus.fromMmkValue(99L))
    }

    @Test
    fun `fromNausysValue maps option, reservation, service and defaults the rest to unknown`() {
        assertEquals(
            ExternalReservationStatus.OPTION,
            ExternalReservationStatus.fromNausysValue(
                org.openapitools.client.nausys.model.RestYachtReservationOccupancy.ReservationType.OPTION,
            ),
        )
        assertEquals(
            ExternalReservationStatus.RESERVATION,
            ExternalReservationStatus.fromNausysValue(
                org.openapitools.client.nausys.model.RestYachtReservationOccupancy.ReservationType.RESERVATION,
            ),
        )
        assertEquals(
            ExternalReservationStatus.SERVICE,
            ExternalReservationStatus.fromNausysValue(
                org.openapitools.client.nausys.model.RestYachtReservationOccupancy.ReservationType.SERVICE,
            ),
        )
        assertEquals(ExternalReservationStatus.UNKNOWN, ExternalReservationStatus.fromNausysValue(null))
    }
}
