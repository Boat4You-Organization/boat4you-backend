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

    /** 100184/2026 — Lavinia Hall, Fountaine Pajot Lucia 40 "Why Not",
     *  Angelina Yachtcharter (HR agency whose address field embeds zip+city
     *  with a newline — exercises the address de-duplication). Single
     *  payment phase; WiFi GRATIS is an in-payment 0.00 row. */
    @Test
    fun `render example agreement for 100184-2026`() {
        val outPath = System.getenv("CHARTER_HARNESS_OUT_100184") ?: return
        val service = buildService()

        val reservation = Reservation().apply {
            id = 16
            reservationNumber = "100184/2026"
            dateFrom = LocalDateTime.of(2026, 8, 22, 17, 0)
            dateTo = LocalDateTime.of(2026, 8, 29, 8, 30)
            currency = "EUR"
            basePrice = BigDecimal("4850.00")
            totalPrice = BigDecimal("3637.50")
            locationFrom = Location().apply {
                name = "Trogir, Marina Trogir (ex.SCT)"
                country = Country().apply { name = "Croatia"; code2 = "HR" }
            }
            reservationFlow = ReservationFlow().apply {
                id = 59
                yacht = Yacht().apply {
                    name = "Why Not"
                    buildYear = 2016
                    length = BigDecimal("11.73")
                    cabins = 4
                    model = Model().apply {
                        name = "Fountaine Pajot Lucia 40"
                        manufacturer = Manufacturer().apply { name = "Fountaine Pajot" }
                    }
                    agency = Agency().apply {
                        name = "Angelina Yachtcharter"
                        address = "Kraljice Jelene 3\n23210 Biograd na Moru"
                        city = "Biograd na Moru"
                        zip = "23210"
                        country = "Croatia"
                        vatCode = "20598733460"
                    }
                }
                user = UserEntity().apply {
                    name = "Lavinia"
                    surname = "Hall"
                    email = "laviniax@gmail.com"
                }
                offer = Offer().apply { deposit = BigDecimal("2000.00") }
                calculatedTotalPrice = BigDecimal("3455.6250")
                reservationExtras = mutableSetOf(
                    ReservationExtra().apply { name = "WiFi GRATIS ON BOAT"; price = BigDecimal("0.00"); payableAtBase = false },
                    ReservationExtra().apply { name = "Charter package"; price = BigDecimal("370.00"); payableAtBase = true },
                )
                paymentPhases = mutableSetOf(phase("3455.63", LocalDate.of(2026, 7, 6)))
            }
        }
        File(outPath).writeBytes(service.renderToPdf(reservation))
        println("Charter agreement written: $outPath")
    }

    /** 1441002/2026 — Bryan Lacy, Bali 4.2 "Chèrie", Croatia Yachting;
     *  skippered (in-payment Skipper 1540), two phases 2677.60. */
    @Test
    fun `render example agreement for 1441002-2026`() {
        val outPath = System.getenv("CHARTER_HARNESS_OUT_1441002") ?: return
        val service = buildService()

        val reservation = Reservation().apply {
            id = 19
            reservationNumber = "1441002/2026"
            dateFrom = LocalDateTime.of(2026, 9, 26, 17, 0)
            dateTo = LocalDateTime.of(2026, 10, 3, 9, 0)
            currency = "EUR"
            basePrice = BigDecimal("5020.00")
            totalPrice = BigDecimal("4016.00")
            locationFrom = Location().apply {
                name = "ACI Marina Dubrovnik"
                country = Country().apply { name = "Croatia"; code2 = "HR" }
            }
            reservationFlow = ReservationFlow().apply {
                id = 62
                yacht = Yacht().apply {
                    name = "Chèrie"
                    buildYear = 2025
                    length = BigDecimal("12.85")
                    cabins = 4
                    model = Model().apply {
                        name = "Bali 4.2"
                        manufacturer = Manufacturer().apply { name = "Bali Catamarans" }
                    }
                    agency = Agency().apply {
                        name = "Croatia Yachting"
                        address = "Dražanac 2/a\n21000 Split"
                        city = "Split"
                        zip = "21000"
                        country = "Croatia"
                        vatCode = "08633766175"
                    }
                }
                user = UserEntity().apply {
                    name = "Bryan"
                    surname = "Lacy"
                    email = "bryanlacy@gmail.com"
                }
                offer = Offer().apply { deposit = BigDecimal("2500.00") }
                calculatedTotalPrice = BigDecimal("5355.2000")
                reservationExtras = mutableSetOf(
                    ReservationExtra().apply { name = "Skipper"; price = BigDecimal("1540.00"); payableAtBase = false },
                    ReservationExtra().apply { name = "Charter pack"; price = BigDecimal("390.00"); payableAtBase = true },
                )
                paymentPhases = mutableSetOf(
                    phase("2677.6", LocalDate.of(2026, 7, 14)),
                    phase("2677.6", LocalDate.of(2026, 8, 27)),
                )
            }
        }
        File(outPath).writeBytes(service.renderToPdf(reservation))
        println("Charter agreement written: $outPath")
    }

    private fun buildService(): CharterAgreementService {
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

        return CharterAgreementService(
            templateEngine,
            settings,
            ClassPathResource("data/images/mario-kuzmanic-signature.png"),
        )
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
