package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.enums.EntryType
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
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.core.io.ClassPathResource
import org.thymeleaf.spring6.SpringTemplateEngine
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * R01 in the charter-agreement PDF (CharterAgreementService :237 / :318): the extras names of an EXTERNAL yacht are
 * printed as customer copy, a CUSTOM yacht's as they are; the charter type still reads the raw stored names.
 */
class CharterAgreementServiceTest {
    private val settings =
        mock(AdminSettingsService::class.java).also {
            `when`(it.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE)).thenReturn(SettingsDto(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE, "5"))
            `when`(it.getSetting(SettingsKeyEnum.BANK_TRANSFER_FIXED_FEE)).thenReturn(SettingsDto(SettingsKeyEnum.BANK_TRANSFER_FIXED_FEE, "32"))
        }
    private val service = CharterAgreementService(SpringTemplateEngine(), settings, ClassPathResource("data/images/mario-kuzmanic-signature.png"))

    private fun yacht(entry: EntryType) =
        Yacht().apply {
            name = "All In"
            entryType = entry
            model = Model().apply {
                name = "Lagoon 42"
                manufacturer = Manufacturer().apply { name = "Lagoon" }
            }
            agency = Agency().apply {
                name = "Athenian Yachts"
                country = "Greece"
            }
        }

    private fun extra(
        extraName: String,
        extraPrice: String,
        atBase: Boolean,
    ) = ReservationExtra().apply {
        name = extraName
        price = BigDecimal(extraPrice)
        payableAtBase = atBase
    }

    private fun flow(entry: EntryType) =
        ReservationFlow().apply {
            id = 70
            yacht = yacht(entry)
            user = UserEntity().apply {
                name = "Test"
                surname = "Charterer"
                email = "charterer@example.com"
            }
            offer = Offer().apply { deposit = BigDecimal("3500.0") }
            calculatedTotalPrice = BigDecimal("3068.50")
            reservationExtras =
                mutableSetOf(
                    extra("Skipper (number of guests to update manually)", "1540", atBase = false),
                    extra("Mooring fees (first and last day at the Athenian's Pier)", "120", atBase = true),
                )
            paymentPhases =
                mutableSetOf(
                    ReservationPaymentPhase().apply {
                        amount = BigDecimal("3068.50")
                        deadline = LocalDate.of(2027, 8, 17)
                    },
                )
        }

    private fun reservation(entry: EntryType) =
        Reservation().apply {
            id = 27
            reservationNumber = "1441003/2027"
            dateFrom = LocalDateTime.of(2027, 10, 2, 17, 0)
            dateTo = LocalDateTime.of(2027, 10, 9, 9, 0)
            currency = "EUR"
            basePrice = BigDecimal("3800.0")
            totalPrice = BigDecimal("3230.00")
            locationFrom = Location().apply {
                name = "Olympic Marina"
                country = Country().apply {
                    name = "Greece"
                    code2 = "GR"
                }
            }
            reservationFlow = flow(entry)
        }

    @Suppress("UNCHECKED_CAST")
    private fun names(
        variables: Map<String, Any?>,
        key: String,
    ) = (variables[key] as List<Map<String, Any?>>).map { it["name"] }

    @Test
    fun `EXTERNAL - the extras names in the agreement are customer copy`() {
        val variables = service.buildVariables(reservation(EntryType.EXTERNAL))
        assertEquals(listOf("Skipper"), names(variables, "inPaymentExtras"))
        assertEquals(listOf("Mooring fees (first and last day at the charter base pier)"), names(variables, "obligatoryExtras"))
        assertEquals("Skippered charter", variables["charterType"], "the charter type still reads the stored name")
    }

    @Test
    fun `CUSTOM - the extras names are printed as stored`() {
        val variables = service.buildVariables(reservation(EntryType.CUSTOM))
        assertEquals(listOf("Skipper (number of guests to update manually)"), names(variables, "inPaymentExtras"))
        assertEquals(listOf("Mooring fees (first and last day at the Athenian's Pier)"), names(variables, "obligatoryExtras"))
    }
}
