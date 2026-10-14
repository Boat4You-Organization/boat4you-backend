package hr.workspace.boat4you.domains.reservation.guard

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySource
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.model.ExtraWrapper
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import org.mockito.Mockito
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.mockito.invocation.InvocationOnMock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Shared builders for the WS-C booking guard tests. Plain Mockito with answer lambdas, like the other tests here. */
object BookingGuardFixtures {
    val WEEK_FROM: LocalDate = LocalDate.of(2027, 7, 3)
    val WEEK_TO: LocalDate = LocalDate.of(2027, 7, 10)
    const val YACHT_ID = 12204L
    const val OFFER_ID = 595995L
    const val FLOW_ID = 83L

    /** A mock whose listed methods answer from [answers]; everything else gets Mockito's defaults. */
    inline fun <reified T> stub(
        calls: MutableList<InvocationOnMock>? = null,
        noinline answers: (InvocationOnMock) -> Any? = { null },
    ): T =
        Mockito.mock(T::class.java) { inv ->
            calls?.add(inv)
            answers(inv) ?: Mockito.RETURNS_DEFAULTS.answer(inv)
        }

    /** A transaction manager mock whose template callbacks get a real (simple) status, like in production. */
    fun txManager(): PlatformTransactionManager =
        stub { inv -> if (inv.method.name == "getTransaction") SimpleTransactionStatus() else null }

    fun agency(
        system: ExternalSystemEnum,
        skipExternalSystem: Boolean = false,
    ): Agency =
        Agency().apply {
            id = 77
            active = true
            this.skipExternalSystem = skipExternalSystem
            agencySources +=
                AgencySource().apply {
                    primary = true
                    externalSystem = ExternalSystem().apply { id = system.value }
                }
        }

    fun yacht(system: ExternalSystemEnum = ExternalSystemEnum.MMK): Yacht =
        Yacht().apply {
            id = YACHT_ID
            name = "Euphoria IV"
            agency = agency(system)
        }

    fun offer(
        yacht: Yacht,
        status: OfferStatus = OfferStatus.FREE,
        extTotalPrice: BigDecimal? = null,
        extClientPrice: BigDecimal? = null,
    ): Offer =
        Offer().apply {
            id = OFFER_ID
            this.yacht = yacht
            dateFrom = WEEK_FROM
            dateTo = WEEK_TO
            this.status = status
            clientPrice = BigDecimal("4800.00")
            this.extTotalPrice = extTotalPrice
            this.extClientPrice = extClientPrice
        }

    fun flow(offer: Offer): ReservationFlow =
        ReservationFlow().apply {
            id = FLOW_ID
            yacht = offer.yacht
            this.offer = offer
            name = "Tom"
            surname = "Tester"
            email = "tom@example.com"
            calculatedTotalPrice = BigDecimal("4800.00")
        }

    /** A clean createOption answer (MMK-like status 2 = option, EUR); vary the rest with `copy(...)`. */
    fun wrapper(
        basePrice: String,
        discount: String,
        clientPrice: String,
        extras: List<ExtraWrapper>? = null,
    ): ReservationResponseWrapper {
        val base = Location().apply { id = 1 }
        return ReservationResponseWrapper(
            externalId = 9_001L,
            externalCode = "RX-9001",
            dateFrom = WEEK_FROM.atTime(17, 0),
            dateTo = WEEK_TO.atTime(9, 0),
            createdAt = LocalDateTime.of(2026, 10, 14, 10, 0),
            expiresAt = LocalDateTime.of(2026, 10, 17, 10, 0),
            product = CharterType.BAREBOAT,
            locationFromId = 1,
            locationToId = 1,
            locationFrom = base,
            locationTo = base,
            status = OfferStatus.OPTION,
            externalStatus = "2",
            basePrice = BigDecimal(basePrice),
            discount = BigDecimal(discount),
            commission = null,
            agencyPrice = null,
            totalPrice = BigDecimal(clientPrice),
            clientPrice = BigDecimal(clientPrice),
            deposit = null,
            currency = "EUR",
            paymentNote = null,
            bankDetails = null,
            note = null,
            extras = extras,
            paymentPlan = null,
            responseBody = null,
            crewListUrl = null,
            yachtId = 4_242L,
            calculatedSysStatus = ReservationStatus.OPTION,
        )
    }

    fun item(
        name: String,
        price: String,
        payableInBase: Boolean,
    ) = ExtraWrapper(externalId = 0, name = name, quantity = 1f, unit = null, price = BigDecimal(price), payableInBase = payableInBase)
}
