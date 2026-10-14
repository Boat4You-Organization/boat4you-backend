package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
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
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.context.support.StaticMessageSource
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R01 in the reservation e-mails: the extras the customer booked and the "available at the marina" list print an
 * EXTERNAL yacht's extras names as customer copy - no "(number of guests to update manually)", no "NSS".
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
}
