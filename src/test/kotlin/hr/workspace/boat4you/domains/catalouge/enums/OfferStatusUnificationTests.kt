package hr.workspace.boat4you.domains.catalouge.enums

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OfferStatusUnificationTests {
    @Test
    fun `toCustomerStatus hard-blocks UNAVAILABLE so booked weeks never read as bookable on boat detail`() {
        // Regression (7.6.2026): the availability overlap-flip sets priced offers that overlap a
        // RESERVATION/SERVICE to UNAVAILABLE. UNAVAILABLE used to fall through to the `else -> FREE`
        // branch, so the boat-detail standard-offers calendar showed every reserved week as
        // "Available". UNAVAILABLE must hard-block.
        assertEquals(ExternalReservationStatus.RESERVATION, OfferStatus.UNAVAILABLE.toCustomerStatus())
    }

    @Test
    fun `toCustomerStatus collapses the 10-state OfferStatus onto the 4 honest customer states`() {
        // Hard-blocked
        assertEquals(ExternalReservationStatus.RESERVATION, OfferStatus.RESERVED.toCustomerStatus())
        assertEquals(ExternalReservationStatus.RESERVATION, OfferStatus.UNAVAILABLE.toCustomerStatus())
        assertEquals(ExternalReservationStatus.SERVICE, OfferStatus.SERVICE.toCustomerStatus())
        // Visible, inquiry-only
        assertEquals(ExternalReservationStatus.OPTION, OfferStatus.OPTION.toCustomerStatus())
        assertEquals(ExternalReservationStatus.OPTION, OfferStatus.OPTION_WAITING.toCustomerStatus())
        // Bookable
        assertEquals(ExternalReservationStatus.FREE, OfferStatus.FREE.toCustomerStatus())
        assertEquals(ExternalReservationStatus.FREE, OfferStatus.OPTION_EXPIRED.toCustomerStatus())
        assertEquals(ExternalReservationStatus.FREE, OfferStatus.CANCELLED.toCustomerStatus())
        assertEquals(ExternalReservationStatus.FREE, OfferStatus.INFO.toCustomerStatus())
        assertEquals(ExternalReservationStatus.FREE, OfferStatus.UNKNOWN.toCustomerStatus())
    }
}
