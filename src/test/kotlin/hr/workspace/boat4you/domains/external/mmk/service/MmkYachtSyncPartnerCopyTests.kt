package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.TranslationType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.Language
import hr.workspace.boat4you.domains.catalouge.jpa.LanguageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslation
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.mmk.model.Description
import org.openapitools.client.mmk.model.Extras
import org.openapitools.client.mmk.model.Product
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * R01 at sync time (MMK): every yacht_extras description the sync writes - insert and update - goes through the
 * partner-copy sanitizer, the extras NAME stays the partner's raw name (a join key, DESIGN D2), and the boat
 * description (yacht_translations) is cleaned too. Repositories are answer-by-method-name mocks.
 */
class MmkYachtSyncPartnerCopyTests {
    private var nextId = 5000L
    private val yachtExtras =
        mock(YachtExtraRepository::class.java) { inv ->
            if (inv.method.name == "save") inv.getArgument<YachtExtra>(0).also { if (it.id == null) it.id = nextId++ } else null
        }
    private val english = Language().apply { locale = LanguageEnum.EN.locale }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        MmkYachtSyncService(
            yachtRepository = any(),
            externalMappingRepository = any(),
            externalSystemService = any(),
            modelRepository = any(),
            externalMappingService = any(),
            yachtImageRepository = any(),
            manufacturerRepository = any(),
            locationQueryingService = any(),
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentLinkResolver = any(),
            modelQueryingService = any(),
            externalEquipmentRepository = any(),
            extraRepository = any(),
            yachtExtraRepository = yachtExtras,
            fileSystemService = any(),
            languageRepository = mock(LanguageRepository::class.java) { inv -> if (inv.method.name == "findAll") listOf(english) else null },
            yachtTranslationRepository = any(),
            agencyRepository = any(),
            modelNameNormaliser = any(),
            realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(),
        )

    private val updatedName = "Early Check-in (14:00pm - to be reconfirmed by NSS office)"
    private val insertedName = "Half board 1-3 pax (number of guests to update manually)"

    private fun extra(
        id: Long,
        name: String,
        description: String?,
        validDaysFrom: Long = 0,
        validDaysTo: Long = 0,
    ) = Extras(
        name = name,
        obligatory = false,
        price = 90f,
        currency = "EUR",
        unit = "per_booking",
        payableInBase = true,
        validDaysFrom = validDaysFrom,
        validDaysTo = validDaysTo,
        id = id,
        description = description,
    )

    @Test
    fun `charter-length bounds are stored on update and on insert`() {
        // Therasia 11907 (22.10.2026): "Luxury Pack Bali 5.4" 0-7 days, "Luxury Pack 2weeks" 8-14 days
        val yacht = yacht()
        val existing =
            YachtExtra().apply {
                id = 100
                externalId = 1
                name = updatedName
                price = BigDecimal("50")
                unit = ExtrasUnitType.PER_BOOKING
                type = ExtrasType.EXTRAS
                this.yacht = yacht
            }
        yacht.yachtExtras.add(existing)

        service.syncExtras(
            yacht,
            listOf(
                Product(
                    name = "Crewed",
                    extras =
                        listOf(
                            extra(1, updatedName, null, validDaysFrom = 0, validDaysTo = 7),
                            extra(2, insertedName, null, validDaysFrom = 8, validDaysTo = 14),
                        ),
                ),
            ),
            optionalOnSite = false,
        )

        assertEquals(0.toShort(), existing.validDaysFrom)
        assertEquals(7.toShort(), existing.validDaysTo)
        val inserted = yacht.yachtExtras.single { it.externalId == 2L }
        assertEquals(8.toShort(), inserted.validDaysFrom)
        assertEquals(14.toShort(), inserted.validDaysTo)
    }

    private fun yacht() =
        Yacht().apply {
            id = 9365
            agency = Agency().apply {
                id = 77
                name = "NSS Charter"
            }
        }

    @Test
    fun `descriptions are cleaned on update and on insert, names stay raw`() {
        val yacht = yacht()
        val existing =
            YachtExtra().apply {
                id = 100
                externalId = 1
                name = updatedName
                price = BigDecimal("50")
                unit = ExtrasUnitType.PER_BOOKING
                type = ExtrasType.EXTRAS
                description = "old"
                this.yacht = yacht
            }
        yacht.yachtExtras.add(existing)

        service.syncExtras(
            yacht,
            listOf(
                Product(
                    name = "Bareboat",
                    extras =
                        listOf(
                            extra(1, updatedName, "To be confirmed by NSS. Athenian Yachts shall not be liable for delays."),
                            extra(2, insertedName, "Served at the Athenian's Pier (number of guests to update manually)"),
                        ),
                ),
            ),
            optionalOnSite = false,
        )

        assertEquals(updatedName, existing.name, "update: the name is the raw partner name")
        assertEquals("To be confirmed by Boat4You.", existing.description, "update: description cleaned")

        val inserted = yacht.yachtExtras.single { it.externalId == 2L }
        assertEquals(insertedName, inserted.name, "insert: the name is the raw partner name")
        assertEquals("Served at the charter base pier", inserted.description, "insert: description cleaned")
    }

    @Test
    fun `a blank or partner-only description is stored as null`() {
        val yacht = yacht()
        service.syncExtras(
            yacht,
            listOf(Product(name = "Bareboat", extras = listOf(extra(3, "Skipper", "  "), extra(4, "Cook", "Athenian Yachts solely provides crew services.")))),
            optionalOnSite = false,
        )
        assertEquals(null, yacht.yachtExtras.single { it.externalId == 3L }.description)
        assertEquals(null, yacht.yachtExtras.single { it.externalId == 4L }.description)
    }

    @Test
    fun `the boat description is cleaned before it is stored`() {
        val yacht = yacht()
        service.createTranslations(
            yacht,
            listOf(Description(category = "general", text = "Lagoon 40 of NSS Charter. Contact info@nss.example for details.")),
            TranslationType.DESCRIPTION,
            LanguageEnum.EN,
        )
        val stored: YachtTranslation = yacht.yachtTranslations.single()
        assertEquals("Lagoon 40 of Boat4You. Contact for details.", stored.value)
    }
}
