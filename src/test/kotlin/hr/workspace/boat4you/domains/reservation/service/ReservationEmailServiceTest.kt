package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.services.CharterAgreementService
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.settings.dto.SettingsDto
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.voucher.jpa.VoucherRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticMessageSource
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Locale
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R01 in the reservation e-mails: the extras the customer booked and the "available at the marina" list print an
 * EXTERNAL yacht's extras names as customer copy - no "(number of guests to update manually)", no "NSS".
 *
 * The pre-charter reminder's checklist: no sailing-licence line for a gulet's guests (Mario 21.10.2026: "sailing
 * licence kod ekipe na guletu ne treba"), every other boat unchanged.
 */
class ReservationEmailServiceTest {
    private val sent = mutableListOf<Map<String, Any?>>()

    @Suppress("UNCHECKED_CAST")
    private val emailService =
        mock(EmailService::class.java) { inv ->
            if (inv.method.name == "sendEmail") sent += inv.arguments[3] as Map<String, Any?>
            null
        }

    private fun service(reservation: Reservation): ReservationEmailService {
        val reservations = mock(ReservationRepository::class.java)
        `when`(reservations.findById(reservation.id!!)).thenReturn(Optional.of(reservation))
        val settings = mock(AdminSettingsService::class.java)
        `when`(settings.getSetting(SettingsKeyEnum.BANK_TRANSFER_FIXED_FEE)).thenReturn(SettingsDto(SettingsKeyEnum.BANK_TRANSFER_FIXED_FEE, "0"))
        return ReservationEmailService(
            emailService = emailService,
            userRepository = mock(UserRepository::class.java),
            reservationRepository = reservations,
            messageSource = StaticMessageSource().apply { setUseCodeAsDefaultMessage(true) },
            charterAgreementService = mock(CharterAgreementService::class.java),
            settingsService = settings,
            voucherRepository = mock(VoucherRepository::class.java),
            serverHost = "https://api.example",
            serverHostPublic = "https://www.example",
            serverHostAdminPublic = "https://admin.example",
        )
    }

    private fun reservation(entry: EntryType): Reservation {
        val yacht =
            Yacht().apply {
                id = 1835
                name = "Violet"
                entryType = entry
                model = Model().apply { name = "Lagoon 42" }
                yachtExtras.add(
                    YachtExtra().apply {
                        id = 1
                        externalId = 900
                        name = "Half board 1-3 pax (number of guests to update manually)"
                        price = BigDecimal("450")
                        unit = ExtrasUnitType.PER_BOOKING
                        type = ExtrasType.EXTRAS
                        obligatory = false
                    },
                )
            }
        return Reservation().apply {
            id = 5
            reservationNumber = "100500/2027"
            currency = "EUR"
            totalPrice = BigDecimal("3000")
            dateFrom = LocalDateTime.of(2027, 7, 3, 17, 0)
            dateTo = LocalDateTime.of(2027, 7, 10, 9, 0)
            locationFrom = Location().apply {
                name = "Le Marin"
                country = Country().apply {
                    name = "Martinique"
                    code2 = "MQ"
                }
            }
            reservationFlow = ReservationFlow().apply {
                id = 50
                email = "charterer@example.com"
                this.yacht = yacht
                user = UserEntity().apply {
                    name = "Test"
                    surname = "Charterer"
                    email = "charterer@example.com"
                }
                reservationExtras.add(
                    ReservationExtra().apply {
                        id = 2
                        externalId = 901
                        name = "Early Check-in (14:00pm - to be reconfirmed by NSS office)"
                        price = BigDecimal("150")
                        unit = ExtrasUnitType.PER_BOOKING
                        payableAtBase = true
                    },
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun names(key: String) = (sent.first()[key] as List<Map<String, Any?>>).map { it["name"] as String }

    @Test
    fun `EXTERNAL - booked extras and the marina list show customer copy`() {
        val reservation = reservation(EntryType.EXTERNAL)
        service(reservation).sendOptionCreatedEmail(reservation.id!!)

        assertEquals(listOf("Early Check-in (14:00pm - to be confirmed by Boat4You)"), names("extrasOnSite"))
        val marina = names("availableAtMarina")
        assertEquals(listOf("Half board 1-3 pax"), marina)
        (marina + names("extrasOnSite")).forEach {
            assertFalse(it.contains("manually"), it)
            assertFalse(it.contains("NSS"), it)
        }
    }

    @Test
    fun `CUSTOM - names are printed as stored`() {
        val reservation = reservation(EntryType.CUSTOM)
        service(reservation).sendOptionCreatedEmail(reservation.id!!)
        assertTrue(names("availableAtMarina").single().endsWith("(number of guests to update manually)"))
    }

    /** The real template with the real English bundle (same basename / encoding as spring.messages). */
    private val templateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    templateMode = TemplateMode.HTML
                    characterEncoding = "UTF-8"
                },
            )
            setTemplateEngineMessageSource(
                ResourceBundleMessageSource().apply {
                    setBasename("messages/email")
                    setDefaultEncoding("UTF-8")
                    setFallbackToSystemLocale(false)
                },
            )
        }

    /** The pre-charter reminder as sent for this yacht: its variables and the rendered "Before you arrive" list. */
    private fun reminder(
        vessel: VesselType?,
        modelName: String,
    ): Pair<Map<String, Any?>, String> {
        val reservation =
            reservation(EntryType.EXTERNAL).apply {
                reservationFlow!!.yacht!!.vesselType = vessel
                reservationFlow!!.yacht!!.model!!.name = modelName
            }
        service(reservation).sendPreCharterReminder(reservation.id!!)
        val variables = sent.single()
        val html = templateEngine.process("email/preCharterReminder", Context(Locale.ENGLISH).apply { setVariables(variables) })
        assertFalse(html.contains("??"), "every message key resolves")
        return variables to html.substringAfter("Before you arrive").substringBefore("</ul>")
    }

    @Test
    fun `pre-charter reminder - a gulet's guests get no sailing-licence line, the rest of the list stays`() {
        listOf(VesselType.GULET to "Custom", VesselType.MOTOR_YACHT to "Gulet").forEach { (vessel, model) ->
            sent.clear()
            val (variables, list) = reminder(vessel, model)
            assertEquals(true, variables["gulet"], "$vessel $model")
            assertFalse(list.contains("sailing licence"), "$vessel $model")
            assertFalse(list.contains("VHF"), "$vessel $model")
            assertEquals(3, Regex("<li[ >]").findAll(list).count(), "$vessel $model")
            assertTrue(list.contains("Bring your passport / ID for every guest on board."))
            assertTrue(list.contains("Pack light layers, swimwear, soft-soled shoes, sunscreen, and any medication you may need."))
            assertTrue(list.contains("Need help? Reply to this email or call +385 91 3000 009."))
        }
    }

    @Test
    fun `pre-charter reminder - every other boat keeps the sailing-licence line`() {
        listOf(VesselType.CATAMARAN to "Lagoon 42", VesselType.SAILING_YACHT to "Oceanis 46.1", null to "Lagoon 42").forEach { (vessel, model) ->
            sent.clear()
            val (variables, list) = reminder(vessel, model)
            assertEquals(false, variables["gulet"], "$vessel $model")
            assertTrue(list.contains("sailing licence and VHF certificate (skipper)"), "$vessel $model")
            assertEquals(4, Regex("<li[ >]").findAll(list).count(), "$vessel $model")
        }
    }
}
