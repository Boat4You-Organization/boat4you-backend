package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.nausys.model.RestYacht
import org.openapitools.client.nausys.model.RestYachtList
import java.util.Optional

/**
 * 25.9.2026 sea charter only: a NauSys yacht whose model is built by an inland-only builder (Linssen), or that is based
 * at an inland location (location.inland, V9_64 - a sea agency's boats on Lake Garda), is never imported, and one
 * imported earlier is switched off (row and mapping kept). A sea builder's yacht at a sea base is synced as usual.
 */
class NauSysYachtSyncInlandSkipTests {
    private val agency =
        Agency().apply {
            id = 1795L
            name = "Hibo Yachtcharter"
        }
    private val seaBase = 10
    private val gardaBase = 1309
    private val existing =
        Yacht().apply {
            id = 88L
            name = "Grand Sturdy 35.0"
            sysActive = true
        }

    // same agency, imported earlier, based at sea
    private val seaYacht =
        Yacht().apply {
            id = 89L
            name = "Sea Yacht"
            sysActive = true
        }
    private val seaLocation = Location().apply { id = 40L }

    // category 101 = MOTOR_YACHT: the vessel type passes, only the builder gives it away
    private val linssenModel =
        Model().apply {
            id = 5L
            name = "Grand Sturdy 35.0"
            manufacturer = Manufacturer().also { it.name = "Linssen" }
            externalCategoryId = 101L
        }
    private val bavariaModel =
        Model().apply {
            id = 5L
            name = "Bavaria C42"
            manufacturer = Manufacturer().also { it.name = "Bavaria" }
            externalCategoryId = 101L
        }
    private var model = linssenModel
    private val saved = mutableListOf<Yacht>()

    private fun mapping(
        externalId: Long,
        systemId: Long,
    ) = ExternalMapping(externalId, systemId, null, null, null)

    private val mappings =
        mock(ExternalMappingService::class.java) { inv ->
            when (inv.method.name) {
                "getAllMappingsByTypeAndExtendedType" -> listOf(mapping(600L, existing.id!!), mapping(602L, seaYacht.id!!))
                // Model mappings (model 700 -> [model]); Location mappings: NauSys sea base -> our sea location
                "getCachedAllMappingsByType" ->
                    if (inv.arguments[0] == "Location") listOf(mapping(seaBase.toLong(), seaLocation.id!!)) else listOf(mapping(700L, model.id!!))
                else -> null
            }
        }
    private val locations =
        mock(LocationQueryingService::class.java) { inv ->
            when (inv.method.name) {
                "getInlandLocationExternalIds" -> setOf(gardaBase.toLong())
                "getCachedLocationById" -> seaLocation
                else -> null
            }
        }
    private val yachts =
        mock(YachtRepository::class.java) { inv ->
            when (inv.method.name) {
                "findById" -> Optional.ofNullable(listOf(existing, seaYacht).find { inv.arguments[0] == it.id })
                "saveAndFlush", "save" -> inv.getArgument<Yacht>(0).also { saved += it }
                else -> null
            }
        }
    private val models =
        mock(ModelRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(model) else null }
    private val agencies =
        mock(AgencyRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(agency) else null }
    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        NauSysYachtSyncService(
            yachtRepository = yachts,
            externalMappingRepository = any(),
            externalSystemService = systems,
            modelRepository = models,
            externalMappingService = mappings,
            yachtImageRepository = any(),
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentRepository = any(),
            locationQueryingService = locations,
            externalEquipmentRepository = any(),
            yachtExtraRepository = any(),
            extraRepository = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            fileSystemService = any(),
            agencyRepository = agencies,
            externalSeasonRepository = any(),
        )

    private fun nausysYacht(
        id: Long,
        locationId: Int = seaBase,
    ) = RestYacht(id = id, name = "Yacht $id", yachtModelId = 700L, locationId = locationId, charterType = "BAREBOAT")

    private fun nausysYachts(vararg ids: Long) = RestYachtList(yachts = ids.map { nausysYacht(it) })

    @Test
    fun `an already imported inland yacht is switched off, a new one is not imported`() {
        service.syncYachtsForAgency(agency.id!!, nausysYachts(600L, 601L))

        existing.sysActive shouldBe false
        saved shouldContainExactly listOf(existing)
    }

    @Test
    fun `a model without a manufacturer is caught by its name`() {
        // Kuhnle-Tours' Kormoran: manufacturer never resolved, the model name still names the builder
        model =
            Model().apply {
                id = 5L
                name = "Kormoran 1140"
                externalCategoryId = 101L
            }

        service.syncYachtsForAgency(agency.id!!, nausysYachts(600L, 601L))

        existing.sysActive shouldBe false
        saved shouldContainExactly listOf(existing)
    }

    @Test
    fun `a sea builder's yacht at a sea base is synced and stays on`() {
        model = bavariaModel

        service.syncYachtsForAgency(agency.id!!, nausysYachts(600L))

        existing.sysActive shouldBe true
        existing.location shouldBe seaLocation
    }

    @Test
    fun `a sea agency's yacht at an inland base is switched off, its yacht at a sea base stays`() {
        // Sail&More: Bavaria at Marina di Navene (Lake Garda) goes off, the same agency's Bavaria at sea is synced as
        // usual; a new one on the lake is not imported
        model = bavariaModel

        service.syncYachtsForAgency(
            agency.id!!,
            RestYachtList(yachts = listOf(nausysYacht(600L, gardaBase), nausysYacht(602L, seaBase), nausysYacht(601L, gardaBase))),
        )

        existing.sysActive shouldBe false
        seaYacht.sysActive shouldBe true
        seaYacht.location shouldBe seaLocation
        saved.distinct() shouldContainExactly listOf(existing, seaYacht)
    }

    @Test
    fun `an inland yacht already off is left alone`() {
        existing.sysActive = false

        service.syncYachtsForAgency(agency.id!!, nausysYachts(600L))

        saved shouldContainExactly emptyList()
    }
}
