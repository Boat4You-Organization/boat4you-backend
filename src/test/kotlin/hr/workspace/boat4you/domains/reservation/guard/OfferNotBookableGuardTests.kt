package hr.workspace.boat4you.domains.reservation.guard

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.exceptions.OfferNotBookableException
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.OFFER_ID
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.WEEK_FROM
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.WEEK_TO
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.YACHT_ID
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.offer
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.stub
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.yacht
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowMutationService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.invocation.InvocationOnMock
import java.util.Optional

/** WS-C1 in createReservationFlow: BOOKING_C1=OFF keeps today's IllegalArgumentException, ON gives 3006. */
class OfferNotBookableGuardTests {
    private var c1 = RealPriceSwitches.OnOff.OFF
    private var system = ExternalSystemEnum.MMK
    private var status = OfferStatus.OPTION
    private var partnerBlocked = false
    private val offerCalls = mutableListOf<InvocationOnMock>()

    private fun bookedOffer(): Offer = offer(yacht(system), status = status)

    private fun service(): ReservationFlowMutationService {
        val week = bookedOffer()
        val yachts: YachtRepository = stub { inv -> if (inv.method.name == "findById") Optional.of(week.yacht!!) else null }
        val offers: OfferRepository = stub(offerCalls) { inv -> if (inv.method.name == "findByIdForUpdate") week else null }
        val busy: ExternalReservationRepository = stub { inv -> if (inv.method.name == "existsBlockingOverlap") partnerBlocked else null }
        val switches: RealPriceSwitches = stub { inv -> if (inv.method.name == "getBookingC1") c1 else null }
        return ReservationFlowMutationService(
            stub(), yachts, offers, busy, stub(), stub(), stub(), stub(), stub(), stub(), stub(), stub(), stub(), stub(),
            stub(), stub(), switches,
        )
    }

    private val request =
        CreateReservationDto(YACHT_ID, OFFER_ID, "tom@example.com", "Tom", "Tester", "+385911234567", null, emptySet())

    private fun noOfferWrite() = offerCalls.filter { it.method.name.startsWith("save") }.shouldBeEmpty()

    @Test
    fun `OFF - a non-FREE week keeps today's IllegalArgumentException`() {
        val e = shouldThrow<Exception> { service().createReservationFlow(request) }
        e.shouldBeInstanceOf<IllegalArgumentException>()
        e.message shouldBe "Offer is not available for reservation"
        noOfferWrite()
    }

    @Test
    fun `OFF - a partner-blocked FREE week keeps today's IllegalArgumentException`() {
        status = OfferStatus.FREE
        partnerBlocked = true
        shouldThrow<Exception> { service().createReservationFlow(request) }.shouldBeInstanceOf<IllegalArgumentException>()
        noOfferWrite()
    }

    @Test
    fun `ON - a non-FREE week is OfferNotBookableException with the exact period and partner`() {
        c1 = RealPriceSwitches.OnOff.ON
        val e = shouldThrow<OfferNotBookableException> { service().createReservationFlow(request) }
        e.offerId shouldBe OFFER_ID
        e.yachtId shouldBe YACHT_ID
        e.dateFrom shouldBe WEEK_FROM
        e.dateTo shouldBe WEEK_TO
        e.externalSystem shouldBe ExternalSystemEnum.MMK
        e.message!! shouldContain "status OPTION"
        noOfferWrite()
    }

    @Test
    fun `ON - a partner-blocked FREE NauSys week is OfferNotBookableException too`() {
        c1 = RealPriceSwitches.OnOff.ON
        system = ExternalSystemEnum.NAUSYS
        status = OfferStatus.FREE
        partnerBlocked = true
        val e = shouldThrow<OfferNotBookableException> { service().createReservationFlow(request) }
        e.externalSystem shouldBe ExternalSystemEnum.NAUSYS
        e.message!! shouldContain "partner reservation"
        noOfferWrite()
    }

    @Test
    fun `ON - an unknown partner system still answers 3006, just without a period to queue`() {
        c1 = RealPriceSwitches.OnOff.ON
        system = ExternalSystemEnum.UNKNOWN
        shouldThrow<OfferNotBookableException> { service().createReservationFlow(request) }.externalSystem shouldBe null
    }
}
