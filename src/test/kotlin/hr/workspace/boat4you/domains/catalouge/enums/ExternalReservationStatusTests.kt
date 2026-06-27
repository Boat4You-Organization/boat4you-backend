package hr.workspace.boat4you.domains.catalouge.enums

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalReservationStatusTests {
    @Test
    fun `clampOptionExpiration keeps the expiry only for OPTION and nulls it for every other status`() {
        val expiry = LocalDateTime.of(2026, 7, 1, 12, 0)
        // An OPTION legitimately carries the lapse timestamp.
        assertEquals(expiry, ExternalReservationStatus.OPTION.clampOptionExpiration(expiry))
        // Zombie guard (root-cause fix): partner feeds echo a stale option expiry after an OPTION is
        // confirmed into a RESERVATION; the sync must drop it so a RESERVATION never becomes a zombie.
        assertNull(ExternalReservationStatus.RESERVATION.clampOptionExpiration(expiry))
        assertNull(ExternalReservationStatus.SERVICE.clampOptionExpiration(expiry))
        assertNull(ExternalReservationStatus.FREE.clampOptionExpiration(expiry))
        assertNull(ExternalReservationStatus.UNKNOWN.clampOptionExpiration(expiry))
        // No expiry to keep stays null even for an OPTION.
        assertNull(ExternalReservationStatus.OPTION.clampOptionExpiration(null))
    }

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
