package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentCatalogueFixture
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentLinkResolver
import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.PartnerEquipmentMapping
import hr.workspace.boat4you.domains.catalouge.jpa.PartnerEquipmentMappingRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipmentRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.mmk.model.EquipmentItemRaw
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MMK equipment sync after the equipment audit (8.10.2026): every agency free-text item (parentId -1) keeps its own row,
 * linked by its own name (they used to collapse into one row per yacht that borrowed another item's link - "Stereo"
 * shown as USB sockets), the link is recomputed on every pass, and an explicit partner_equipment_mapping row beats the
 * matcher. Real resolver and matcher on the R__1_05 catalogue; repositories are answer-by-method-name mocks.
 */
class MmkYachtSyncEquipmentTests {
    private val catalogue = EquipmentCatalogueFixture.catalogue()

    private fun equipment(label: String): Equipment = catalogue.single { it.labelCode == label }

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
    private val mmkCatalogue =
        listOf(21L to "Wi-Fi & Internet", 37L to "Refrigerator", 9L to "Bow thruster").map { (id, name) ->
            ExternalEquipment().apply {
                externalId = id
                this.name = name
                type = ExternalEquipmentType.EQUIPMENT
            }
        }
    private val externalEquipment =
        mock(ExternalEquipmentRepository::class.java) { inv -> if (inv.method.name == "getCachedByExternalSystemId") mmkCatalogue else null }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private fun resolver(extraMappings: List<PartnerEquipmentMapping>): EquipmentLinkResolver {
        val equipmentRepository = mock(EquipmentRepository::class.java) { inv -> if (inv.method.name == "findAllByOrderByIdAsc") catalogue else null }
        val mappings = EquipmentCatalogueFixture.mappings(catalogue) + extraMappings
        val mappingRepository = mock(PartnerEquipmentMappingRepository::class.java) { inv -> if (inv.method.name == "findAllForResolver") mappings else null }
        return EquipmentLinkResolver(equipmentRepository, mappingRepository)
    }

    private fun service(resolver: EquipmentLinkResolver) =
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
            yachtEquipmentRepository = yachtEquipments,
            equipmentLinkResolver = resolver,
            modelQueryingService = any(),
            externalEquipmentRepository = externalEquipment,
            extraRepository = any(),
            yachtExtraRepository = any(),
            fileSystemService = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            agencyRepository = any(),
            modelNameNormaliser = any(),
        )

    private var rawId = 1L

    private fun item(
        parentId: Long,
        name: String,
        value: String = "",
    ) = EquipmentItemRaw(id = rawId++, parentId = parentId, name = name, value = value, categoryName = "Other")

    /** An existing row as loaded from the DB (equipmentId mirrors the FK). */
    private fun row(
        id: Long,
        externalId: Long?,
        name: String,
        link: String?,
        comment: String? = null,
    ) = YachtEquipment().apply {
        this.id = id
        this.externalId = externalId
        this.name = name
        equipment = link?.let { equipment(it) }
        equipmentId = equipment?.id
        this.comment = comment
    }

    private fun yacht(vararg rows: YachtEquipment) =
        Yacht().apply {
            id = 13960L
            yachtEquipments.addAll(rows)
            rows.forEach { it.yacht = this }
        }

    /** One pass over one yacht, as syncYachtsForAgency runs it (one resolver pass per agency). */
    private fun sync(
        yacht: Yacht,
        items: List<EquipmentItemRaw>,
        extraMappings: List<PartnerEquipmentMapping> = emptyList(),
    ) {
        val resolver = resolver(extraMappings)
        service(resolver).syncEquipment(yacht, items.toSet(), null, lazy { resolver.newPass() })
    }

    private fun Yacht.byName(): Map<String?, YachtEquipment> = yachtEquipments.associateBy { it.name }

    @Test
    fun `the collapsed free-text row splits - every item its own row, linked by its own name, comment on its own row`() {
        // prod 8.10.: ONE -1 row per yacht - the first item's name, another item's link and the last item's comment
        val collapsed = row(335543, -1, "Stereo", link = "usb-sockets", comment = "2")
        val yacht = yacht(collapsed)

        sync(yacht, listOf(item(-1, "Stereo"), item(-1, "USB sockets", "2"), item(-1, "Furling Genoa", "true")))

        val rows = yacht.byName()
        assertEquals(setOf("Stereo", "USB sockets", "Furling Genoa"), rows.keys)
        assertEquals(335543L, rows.getValue("Stereo").id, "the existing row is kept for its own name")
        assertEquals("audio-system", rows.getValue("Stereo").equipment?.labelCode)
        assertEquals(null, rows.getValue("Stereo").comment)
        assertEquals("usb-sockets", rows.getValue("USB sockets").equipment?.labelCode)
        assertEquals("2", rows.getValue("USB sockets").comment)
        assertEquals(null, rows.getValue("Furling Genoa").equipment, "no code for a sail: a row, but no link")
        assertEquals("true", rows.getValue("Furling Genoa").comment)
        assertTrue(rows.values.all { it.externalId == -1L })
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `the same free-text name twice in one payload is one row with the first comment`() {
        val yacht = yacht()

        sync(yacht, listOf(item(-1, "Fridge", "130 L"), item(-1, "FRIDGE ", "90 L")))

        assertEquals(1, yacht.yachtEquipments.size)
        val fridge = yacht.yachtEquipments.single()
        assertEquals("Fridge", fridge.name)
        assertEquals("130 L", fridge.comment)
        assertEquals("fridge", fridge.equipment?.labelCode)
    }

    @Test
    fun `a free-text row is found again by its normalised name, a vanished item's row is deleted`() {
        val stereo = row(10, -1, "Stereo", link = "audio-system")
        val gone = row(11, -1, "Yanmar", link = "generator")
        val yacht = yacht(stereo, gone)

        sync(yacht, listOf(item(-1, "STEREO")))

        assertEquals(listOf(stereo), yacht.yachtEquipments.toList())
        assertEquals("audio-system", stereo.equipment?.labelCode)
        assertEquals(listOf(gone), deleted)
    }

    @Test
    fun `a catalogue item never adopts a free-text row of the same name`() {
        val freeText = row(20, -1, "Refrigerator", link = "fridge")
        val yacht = yacht(freeText)

        sync(yacht, listOf(item(37, "Refrigerator 130 L")))

        val catalogueRow = yacht.yachtEquipments.single()
        assertEquals(37L, catalogueRow.externalId)
        assertEquals("Refrigerator", catalogueRow.name, "a catalogue item is named after the partner catalogue")
        assertEquals("fridge", catalogueRow.equipment?.labelCode, "refrigerator is an alias of fridge")
        assertEquals(listOf(freeText), deleted)
    }

    @Test
    fun `a legacy row without external_id is adopted by a catalogue item of the same name`() {
        val legacy = row(30, null, "Wi-Fi & Internet", link = null)
        val yacht = yacht(legacy)

        sync(yacht, listOf(item(21, "Wi-Fi & Internet")))

        assertEquals(listOf(legacy), yacht.yachtEquipments.toList())
        assertEquals("wifi", legacy.equipment?.labelCode)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `the link is recomputed on every pass - newly linked, relinked, and back to NULL`() {
        val wifi = row(40, 21, "Wi-Fi & Internet", link = null)
        val bowThruster = row(41, 9, "Bow thruster", link = "bow-thruster-deck")
        val yanmar = row(42, -1, "Yanmar", link = "generator")
        val yacht = yacht(wifi, bowThruster, yanmar)

        sync(yacht, listOf(item(21, "Wi-Fi & Internet"), item(9, "Bow thruster"), item(-1, "Yanmar")))

        assertEquals("wifi", wifi.equipment?.labelCode)
        assertEquals("bow-thruster", bowThruster.equipment?.labelCode, "never back to the alias")
        assertEquals(null, yanmar.equipment, "an engine brand is not a generator")
        assertEquals(3, yacht.yachtEquipments.size)
    }

    @Test
    fun `an explicit mapping beats the matcher, and its NULL means no link`() {
        val noWifi =
            PartnerEquipmentMapping().apply {
                id = 900L
                externalSystemId = 1
                partnerItemId = 21L
                partnerNameNorm = ""
                equipmentId = null
                note = "test"
            }
        val yacht = yacht(row(50, 21, "Wi-Fi & Internet", link = "wifi"))

        sync(yacht, listOf(item(21, "Wi-Fi & Internet"), item(-1, "Radio on the flybridge"), item(-1, "Banana")), listOf(noWifi))

        val rows = yacht.byName()
        assertEquals(null, rows.getValue("Wi-Fi & Internet").equipment)
        assertEquals("audio-system", rows.getValue("Radio on the flybridge").equipment?.labelCode, "V9_74 seed; matcher alone: flybridge")
        assertEquals("water-toys", rows.getValue("Banana").equipment?.labelCode, "V9_74 seed; matcher alone: nothing")
    }
}
