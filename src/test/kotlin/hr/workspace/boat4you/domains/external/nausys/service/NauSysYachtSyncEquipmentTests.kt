package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentCatalogueFixture
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentLinkResolver
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.PartnerEquipmentMappingRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipmentRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.nausys.model.RestInternationalText
import org.openapitools.client.nausys.model.RestYacht
import org.openapitools.client.nausys.model.RestYachtEquipment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NauSys equipment sync after the equipment audit (8.10.2026): the link is recomputed on every pass (it used to be set
 * once, when NULL, so "Life jackets" stayed on life-buoy forever), and only a legacy row without external_id is adopted
 * by name. Real resolver and matcher on the R__1_05 catalogue; repositories are answer-by-method-name mocks.
 */
class NauSysYachtSyncEquipmentTests {
    private val catalogue = EquipmentCatalogueFixture.catalogue()

    private var nextId = 1000L
    private val deleted = mutableListOf<YachtEquipment>()
    private val yachtEquipments =
        mock(YachtEquipmentRepository::class.java) { inv ->
            when (inv.method.name) {
                "save" -> inv.getArgument<YachtEquipment>(0).also { if (it.id == null) it.id = nextId++ }
                "deleteAll" -> {
                    deleted += inv.getArgument<Iterable<YachtEquipment>>(0)
                    null
                }
                else -> null
            }
        }
    private val nausysCatalogue =
        listOf(100664L to "Life jackets", 845150L to "Stove", 477829L to "Wi-Fi Internet", 100623L to "Fish finder").map { (id, name) ->
            ExternalEquipment().apply {
                externalId = id
                this.name = name
                type = ExternalEquipmentType.EQUIPMENT
            }
        }
    private val resolver =
        EquipmentLinkResolver(
            mock(EquipmentRepository::class.java) { inv -> if (inv.method.name == "findAllByOrderByIdAsc") catalogue else null },
            mock(PartnerEquipmentMappingRepository::class.java) { inv -> if (inv.method.name == "findAllForResolver") emptyList<Any>() else null },
        )

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
            yachtEquipmentRepository = yachtEquipments,
            equipmentLinkResolver = resolver,
            locationQueryingService = any(),
            externalEquipmentRepository =
                mock(ExternalEquipmentRepository::class.java) { inv ->
                    if (inv.method.name == "getCachedByExternalSystemId") nausysCatalogue else null
                },
            yachtExtraRepository = any(),
            extraRepository = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            fileSystemService = any(),
            agencyRepository = any(),
            externalSeasonRepository = any(),
        )

    private fun item(
        equipmentId: Long,
        comment: String? = null,
        highlight: Boolean = false,
    ) = RestYachtEquipment(equipmentId = equipmentId.toInt(), highlight = highlight, comment = comment?.let { RestInternationalText(textEN = it) })

    private fun row(
        id: Long,
        externalId: Long?,
        name: String,
        link: String?,
    ) = YachtEquipment().apply {
        this.id = id
        this.externalId = externalId
        this.name = name
        equipment = link?.let { label -> catalogue.single { it.labelCode == label } }
        equipmentId = equipment?.id
    }

    private fun yacht(vararg rows: YachtEquipment) =
        Yacht().apply {
            id = 4066L
            yachtEquipments.addAll(rows)
            rows.forEach { it.yacht = this }
        }

    private fun sync(
        yacht: Yacht,
        vararg items: RestYachtEquipment,
    ) = service.syncEquipment(yacht, RestYacht(standardYachtEquipment = items.toList()), lazy { resolver.newPass() })

    @Test
    fun `a stored link is recomputed - relinked, newly linked and unlinked`() {
        val lifeJackets = row(1, 100664, "Life jackets", link = "life-buoy")
        val wifi = row(2, 477829, "Wi-Fi Internet", link = null)
        val fishFinder = row(3, 100623, "Fish finder", link = "fenders")
        val yacht = yacht(lifeJackets, wifi, fishFinder)

        sync(yacht, item(100664), item(477829), item(100623))

        assertEquals("life-jackets", lifeJackets.equipment?.labelCode)
        assertEquals("wifi", wifi.equipment?.labelCode)
        assertEquals(null, fishFinder.equipment)
        assertEquals(3, yacht.yachtEquipments.size)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `only a legacy row without external_id is adopted by name`() {
        val legacy = row(10, null, "Stove", link = null)
        val yacht = yacht(legacy)

        sync(yacht, item(845150, comment = "4 burners"))

        assertEquals(listOf(legacy), yacht.yachtEquipments.toList())
        assertEquals("cooker", legacy.equipment?.labelCode)
        assertEquals("4 burners", legacy.comment)
    }

    @Test
    fun `another item's row of the same name is not adopted`() {
        val otherItem = row(20, 999, "Stove", link = "cooker")
        val yacht = yacht(otherItem)

        sync(yacht, item(845150))

        val own = yacht.yachtEquipments.single()
        assertEquals(845150L, own.externalId)
        assertEquals("cooker", own.equipment?.labelCode)
        assertEquals(listOf(otherItem), deleted)
    }

    @Test
    fun `the same item twice in one payload keeps the first occurrence`() {
        val yacht = yacht()

        sync(yacht, item(845150, comment = "gas", highlight = true), item(845150, comment = "induction"))

        val stove = yacht.yachtEquipments.single()
        assertEquals("gas", stove.comment)
        assertEquals(true, stove.highlight)
    }
}
