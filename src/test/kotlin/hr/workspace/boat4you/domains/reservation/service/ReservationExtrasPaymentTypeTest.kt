package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.dto.ExtrasPriceDto
import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.PriceCalculationService
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtraRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.mapper.ReservationMappers
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.UserInviteService
import hr.workspace.boat4you.domains.users.services.UserMutationService
import hr.workspace.boat4you.domains.voucher.service.VoucherService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional
import kotlin.test.assertEquals

/** D6 (14.10.2026): every booked extra stores the payment type of the price calculation it was booked with. */
class ReservationExtrasPaymentTypeTest {
    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    /** A Mockito mock answering by method name (no mockito-kotlin / MockK on the classpath). */
    private inline fun <reified T> stub(crossinline answer: (InvocationOnMock) -> Any?): T = mock(T::class.java) { answer(it) }

    private val agency = Agency().apply { active = true }
    private val yacht =
        Yacht().apply {
            id = 18886
            entryType = EntryType.EXTERNAL
            this.agency = this@ReservationExtrasPaymentTypeTest.agency
        }
    private val offer =
        Offer().apply {
            id = 5
            yacht = this@ReservationExtrasPaymentTypeTest.yacht
            status = OfferStatus.FREE
            dateFrom = LocalDate.of(2027, 7, 3)
            dateTo = LocalDate.of(2027, 7, 10)
            clientPrice = BigDecimal("4200.00")
        }

    private fun extra(
        id: Long,
        name: String,
        price: String,
        obligatory: Boolean,
        paymentType: ExtraPaymentType?,
    ) = ExtrasPriceDto(
        id = id,
        name = name,
        labelCode = null,
        priceEur = BigDecimal(price),
        priceInfo = null,
        obligatory = obligatory,
        payableInBase = false,
        unit = ExtrasUnitType.PER_BOOKING,
        unitPriceEur = BigDecimal(price),
        unitPriceInfo = null,
        key = name,
        extrasId = null,
        externalId = null,
        isStartingPrice = null,
        paymentType = paymentType,
    )

    private fun calc(
        inPrice: List<ExtrasPriceDto>,
        atBase: List<ExtrasPriceDto>,
    ) = PriceCalcDto(
        offerId = 5,
        yachtId = 18886,
        dateFrom = offer.dateFrom!!,
        dateTo = offer.dateTo!!,
        clientPricePerDayEur = BigDecimal("600.00"),
        clientPricePerDayInfo = null,
        numberOfDays = 7,
        clientPriceEur = BigDecimal("4200.00"),
        clientPriceInfo = null,
        totalPriceEur = BigDecimal("4450.00"),
        totalPriceInfo = null,
        totalDiscountEur = BigDecimal.ZERO,
        totalDiscountInfo = null,
        selectedExtrasAtBase = atBase,
        selectedExtrasInPrice = inPrice,
        inquire = false,
        securityDeposit = null,
        insuredSecurityDeposit = null,
        depositCurrency = null,
        multipleExtrasInPriceOptionsAvailable = false,
        multipleExtrasInBaseOptionsAvailable = false,
        extrasPriceCalcInquire = false,
    )

    private val offers =
        stub<OfferRepository> {
            when (it.method.name) {
                "findByIdForUpdate" -> offer
                "save" -> it.getArgument(0)
                else -> null
            }
        }

    private val flows =
        stub<ReservationFlowRepository> {
            when (it.method.name) {
                "save" -> it.getArgument<ReservationFlow>(0).apply { id = 900 }
                "saveAndFlush" -> it.getArgument(0)
                else -> null
            }
        }

    private fun service(
        price: PriceCalcDto,
        saved: MutableList<ReservationExtra>,
    ) = ReservationFlowMutationService(
        userRepository = stub { if (it.method.name == "findByEmail") UserEntity().apply { id = 77 } else null },
        yachtRepository = stub<YachtRepository> { if (it.method.name == "findById") Optional.of(yacht) else null },
        offerRepository = offers,
        externalReservationRepository =
            stub<ExternalReservationRepository> { if (it.method.name == "existsBlockingOverlap") false else null },
        reservationFlowRepository = flows,
        userMutationService = mock(UserMutationService::class.java),
        extraRepository = mock(ExtraRepository::class.java),
        reservationExtraRepository =
            stub<ReservationExtraRepository> {
                if (it.method.name == "save") it.getArgument<ReservationExtra>(0).also { e -> saved += e } else null
            },
        priceCalculationService = stub<PriceCalculationService> { if (it.method.name == "calculatePrice") price else null },
        paymentPhaseRepository = mock(ReservationPaymentPhaseRepository::class.java),
        paymentPhasesService =
            stub<ReservationPaymentPhasesService> { if (it.method.name == "calculatePaymentPhases") emptyList<Any>() else null },
        inviteService = mock(UserInviteService::class.java),
        reservationRepository = mock(ReservationRepository::class.java),
        reservationMappers = mock(ReservationMappers::class.java),
        bookingNumberService = mock(BookingNumberService::class.java),
        voucherService = mock(VoucherService::class.java),
    )

    @Test
    fun `booked extras carry the paymentType of the price calculation`() {
        val saved = mutableListOf<ReservationExtra>()
        val price =
            calc(
                inPrice = listOf(extra(1, "Transit log", "250", obligatory = true, ExtraPaymentType.WITH_BOOKING)),
                atBase =
                    listOf(
                        extra(2, "Skipper", "0", obligatory = false, ExtraPaymentType.ON_SITE),
                        extra(3, "Wifi", "0", obligatory = true, ExtraPaymentType.INCLUDED),
                        extra(4, "APA", "1200", obligatory = false, ExtraPaymentType.ADVANCE_TO_OPERATOR),
                        extra(5, "Legacy", "10", obligatory = false, paymentType = null),
                    ),
            )
        val service = service(price, saved)

        val flowId =
            service.createReservationFlow(
                CreateReservationDto(
                    yachtId = 18886,
                    offerId = 5,
                    email = "guest@example.com",
                    name = "Ana",
                    surname = "Horvat",
                    phoneNumber = null,
                    specialRequest = null,
                    selectedExtras = setOf("Skipper", "APA", "Legacy"),
                ),
            )

        assertEquals(900L, flowId)
        assertEquals<Map<String?, ExtraPaymentType?>>(
            mapOf(
                "Transit log" to ExtraPaymentType.WITH_BOOKING,
                "Skipper" to ExtraPaymentType.ON_SITE,
                "Wifi" to ExtraPaymentType.INCLUDED,
                "APA" to ExtraPaymentType.ADVANCE_TO_OPERATOR,
                "Legacy" to null,
            ),
            saved.associate { it.name to it.paymentType },
        )
        // The existing bucket flags are untouched.
        assertEquals(false, saved.single { it.name == "Transit log" }.payableAtBase)
        assertEquals(true, saved.single { it.name == "Skipper" }.payableAtBase)
    }
}
