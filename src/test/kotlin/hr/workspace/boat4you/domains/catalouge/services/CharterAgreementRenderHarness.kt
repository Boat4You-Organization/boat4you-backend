package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.settings.dto.SettingsDto
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.io.ClassPathResource
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Render harness, not a regression test: builds the charter-agreement PDF
 * for a hand-populated copy of a REAL reservation so the template rework
 * can be eyeballed before it ships (Mario reviews the PDF, then we deploy).
 * Data below mirrors booking 1441003/2027 as read from the prod DB on
 * 14.8.2026. Writes to the path in CHARTER_HARNESS_OUT (skipped when the
 * env var is absent, so CI never depends on it).
 */
class CharterAgreementRenderHarness {

    @Test
    fun `render example agreement for 1441003-2027`() {
        val outPath = System.getenv("CHARTER_HARNESS_OUT") ?: return

        val templateEngine = SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "/templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }
        val settings = mock(AdminSettingsService::class.java)
        `when`(settings.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE))
            .thenReturn(SettingsDto(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE, "5"))
        `when`(settings.getSetting(SettingsKeyEnum.BANK_TRANSFER_FIXED_FEE))
            .thenReturn(SettingsDto(SettingsKeyEnum.BANK_TRANSFER_FIXED_FEE, "32"))

        val service = CharterAgreementService(
            templateEngine,
            settings,
            ClassPathResource("data/images/mario-kuzmanic-signature.png"),
        )

        val agency = Agency().apply {
            name = "White Blue Seas"
            address = "104, Saki Karagiorga Str."
            city = "Athens"
            zip = "16675"
            country = "Greece"
            vatCode = "801229132"
            phone = "+30 2109607902"
            mobile = "+30 6972058020"
            email = "info@whiteblueseas.com"
        }
        val yacht = Yacht().apply {
            name = "All In"
            buildYear = 2020
            length = BigDecimal("12.8")
            cabins = 4
            model = Model().apply {
                name = "Lagoon 42"
                manufacturer = Manufacturer().apply { name = "Lagoon" }
            }
            this.agency = agency
        }
        val user = UserEntity().apply {
            name = "Knut-Ove"
            surname = "Kvarme"
            email = "kokvarme@gmail.com"
        }
        val flow = ReservationFlow().apply {
            id = 70
            this.yacht = yacht
            this.user = user
            offer = Offer().apply { deposit = BigDecimal("3500.0") }
            calculatedTotalPrice = BigDecimal("3068.500")
            reservationExtras = mutableSetOf(
                extra("Stand Up Paddle (SUP)", "100.0"),
                extra("Fishing rod", "100.0"),
                extra("Early Boarding", "150.0"),
                extra("Safety Net for Kids (installed)", "300.0"),
                extra("Charter Pack Cat 42 - 45 ft. (Incl. End Cleaning, Bed Linen, Towels & Outboard)", "320.0"),
            )
            paymentPhases = mutableSetOf(
                phase("1534.25", LocalDate.of(2026, 8, 17)),
                phase("1534.25", LocalDate.of(2027, 8, 18)),
            )
        }
        val reservation = Reservation().apply {
            id = 27
            reservationNumber = "1441003/2027"
            reservationFlow = flow
            dateFrom = LocalDateTime.of(2027, 10, 2, 17, 0)
            dateTo = LocalDateTime.of(2027, 10, 9, 9, 0)
            currency = "EUR"
            basePrice = BigDecimal("3800.0")
            totalPrice = BigDecimal("3230.0002")
            locationFrom = Location().apply {
                name = "Olympic Marina"
                country = Country().apply {
                    name = "Greece"
                    code2 = "GR"
                }
            }
        }

        val pdf = service.renderToPdf(reservation)
        File(outPath).writeBytes(pdf)
        println("Charter agreement written: $outPath (${pdf.size} bytes)")
    }

    private fun extra(extraName: String, extraPrice: String): ReservationExtra = ReservationExtra().apply {
        name = extraName
        price = BigDecimal(extraPrice)
        payableAtBase = true
    }

    private fun phase(phaseAmount: String, phaseDeadline: LocalDate): ReservationPaymentPhase =
        ReservationPaymentPhase().apply {
            amount = BigDecimal(phaseAmount)
            deadline = phaseDeadline
        }
}
