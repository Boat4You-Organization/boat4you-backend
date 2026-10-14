package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.TranslationType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Language
import hr.workspace.boat4you.domains.catalouge.jpa.LanguageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.nausys.model.RestInternationalText
import org.openapitools.client.nausys.model.RestYacht
import org.openapitools.client.nausys.model.RestYachtAdditionalEquipmentPrice
import org.openapitools.client.nausys.model.RestYachtSeason
import org.openapitools.client.nausys.model.RestYachtServicePrice
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * R01 at sync time (NauSys): equipment comments and service descriptions written to yacht_extras - insert and update -
 * go through the partner-copy sanitizer, the extras NAME stays the partner catalogue name (a join key the obligatory
 * lookup maps back to a serviceId, DESIGN D2), and the boat description (yacht_translations, NOT NULL) is cleaned too.
 */
class NauSysYachtSyncPartnerCopyTests {
    private var nextId = 7000L
    private val yachtExtras =
        mock(YachtExtraRepository::class.java) { inv ->
            if (inv.method.name == "save") inv.getArgument<YachtExtra>(0).also { if (it.id == null) it.id = nextId++ } else null
        }
    private val serviceName = "Half board 1-3 pax (number of guests to update manually)"
    private val equipmentName = "Navbow Seascooter (+ deposit 600€ - to be confirmed by NSS)"
    private val partnerCatalogue =
        listOf(
            ExternalEquipment().apply {
                externalId = 501
                name = serviceName
                type = ExternalEquipmentType.SERVICE
            },
            ExternalEquipment().apply {
                externalId = 601
                name = equipmentName
                type = ExternalEquipmentType.EQUIPMENT
            },
        )
    private val languages = LanguageEnum.entries.filter { it == LanguageEnum.EN || it == LanguageEnum.DE }.map { l -> Language().apply { locale = l.locale } }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        NauSysYachtSyncService(
            yachtRepository = any(),
            externalMappingRepository = any(),
            externalSystemService = any(),
            modelRepository = any(),
            externalMappingService = any(),
            yachtImageRepository = any(),
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentLinkResolver = any(),
            locationQueryingService = any(),
            externalEquipmentRepository =
                mock(ExternalEquipmentRepository::class.java) { inv ->
                    if (inv.method.name == "getCachedByExternalSystemId") partnerCatalogue else null
                },
            yachtExtraRepository = yachtExtras,
            extraRepository = any(),
            languageRepository = mock(LanguageRepository::class.java) { inv -> if (inv.method.name == "findAll") languages else null },
            yachtTranslationRepository = any(),
            fileSystemService = any(),
            agencyRepository = any(),
            externalSeasonRepository = any(),
            realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(),
        )

    private fun yacht() =
        Yacht().apply {
            id = 1835
            agency = Agency().apply {
                id = 88
                name = "NSS Charter"
            }
        }

    private fun nausysYacht() =
        RestYacht(
            seasonSpecificData =
                listOf(
                    RestYachtSeason(
                        seasonId = 1L,
                        services =
                            listOf(
                                RestYachtServicePrice(
                                    id = 11L,
                                    serviceId = 501L,
                                    amount = "90",
                                    priceMeasureId = 54L,
                                    description = RestInternationalText(textEN = "Hermes Yachting shall not be liable. Paid on board."),
                                ),
                                RestYachtServicePrice(
                                    id = 12L,
                                    serviceId = 501L,
                                    amount = "90",
                                    priceMeasureId = 54L,
                                    description = RestInternationalText(textEN = "Check-in at the NSS office: tel. +590 690 12 34 56"),
                                ),
                            ),
                        additionalYachtEquipment =
                            listOf(
                                RestYachtAdditionalEquipmentPrice(
                                    id = 21L,
                                    equipmentId = 601L,
                                    amount = "120",
                                    priceMeasureId = 54L,
                                    comment = RestInternationalText(textEN = "Deposit at the Ionian Catamarans' pontoon"),
                                ),
                            ),
                    ),
                ),
        )

    @Test
    fun `service descriptions and equipment comments are cleaned on update and insert, names stay raw`() {
        val yacht = yacht()
        val existingService =
            YachtExtra().apply {
                id = 100
                externalId = 11
                name = serviceName
                price = BigDecimal("90")
                unit = ExtrasUnitType.PER_BOOKING
                type = ExtrasType.EXTRAS
                description = "old"
                this.yacht = yacht
            }
        yacht.yachtExtras.add(existingService)

        val nausysYacht = nausysYacht()

        service.syncExtras(yacht, nausysYacht, optionalOnSite = false)

        assertEquals(serviceName, existingService.name, "update: the name stays the partner name")
        assertEquals("Paid on board.", existingService.description)

        val insertedService = yacht.yachtExtras.single { it.externalId == 12L }
        assertEquals(serviceName, insertedService.name, "insert: the name stays the partner name")
        assertEquals("Check-in at the charter base office:", insertedService.description)

        val insertedEquipment = yacht.yachtExtras.single { it.externalId == 21L }
        assertEquals(equipmentName, insertedEquipment.name)
        assertEquals("Deposit at the charter base pontoon", insertedEquipment.description)
    }

    @Test
    fun `the boat description is cleaned in every locale and never stored as null`() {
        val yacht = yacht()
        service.createTranslations(
            yacht,
            RestInternationalText(textEN = "Sunsail 41.3 Premium by Sunsail.", textDE = "Athenian Yachts solely provides crew services."),
            TranslationType.DESCRIPTION,
        )
        val byLocale = yacht.yachtTranslations.associate { it.language!!.locale to it.value }
        assertEquals("Boat4You 41.3 Premium by Boat4You.", byLocale[LanguageEnum.EN.locale])
        assertEquals("", byLocale[LanguageEnum.DE.locale], "a disclaimer-only text is stored empty, never NULL (NOT NULL column)")
    }
}
