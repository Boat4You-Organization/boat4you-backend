package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.ManufacturerAliasResolver
import hr.workspace.boat4you.domains.catalouge.services.ModelNameNormaliser
import hr.workspace.boat4you.domains.catalouge.utils.BrandModelAliases
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.nausys.model.RestYachtModel
import org.openapitools.client.nausys.model.RestYachtModelList
import java.math.BigDecimal

/**
 * R02 (14.10.2026) on the NauSys side (the names: YachtNamePolicyTest.applyTo): the catalogue sync (modelsSync) keeps an existing model's manufacturer (G2), maps an aliased charter-brand label to the builder model without
 * creating it or overwriting its maker / dimensions (G3), and never sets a charter brand as a model's manufacturer (G5).
 */
class NauSysR02ModelGuardsTests {
    private val leopard =
        Manufacturer().apply {
            id = 1061L
            name = "Leopard Catamarans"
        }
    private val sunsail =
        Manufacturer().apply {
            id = 561L
            name = "Sunsail"
        }
    private val jeanneau =
        Manufacturer().apply {
            id = 5L
            name = "Jeanneau"
        }

    // NauSys builder ids -> our manufacturers
    private val builderLeopard = 10
    private val builderSunsail = 11
    private val builderJeanneau = 12

    private val leopard42 =
        Model().apply {
            id = 2234L
            name = "Leopard 42"
            manufacturer = leopard
            length = BigDecimal("12.67")
        }
    private val sunOdyssey =
        Model().apply {
            id = 300L
            name = "Sun Odyssey 410"
            manufacturer = jeanneau
        }
    private val existingModels = mutableListOf(leopard42, sunOdyssey)
    private val modelMappings = mutableListOf<ExternalMapping>()
    private val savedMappings = mutableListOf<String>()
    private val flushed = mutableListOf<Model>()
    private var nextId = 9000L

    private val models =
        mock(ModelRepository::class.java) { inv ->
            when (inv.method.name) {
                "findAll" -> existingModels.toList()
                "findAllByNameIgnoreCaseAndExternalManufacturerId" -> emptyList<Model>()
                "saveAndFlush" ->
                    inv.getArgument<Model>(0).also {
                        if (it.id == null) it.id = nextId++
                        flushed += it
                    }
                else -> null
            }
        }
    private val mappings =
        mock(ExternalMappingService::class.java) { inv ->
            when (inv.method.name) {
                "getAllMappingsByType" ->
                    if (inv.arguments[0] == "Model") {
                        modelMappings.toList()
                    } else {
                        listOf(
                            ExternalMapping(builderLeopard.toLong(), leopard.id, null, null, null),
                            ExternalMapping(builderSunsail.toLong(), sunsail.id, null, null, null),
                            ExternalMapping(builderJeanneau.toLong(), jeanneau.id, null, null, null),
                        )
                    }
                "saveMapping" -> {
                    savedMappings += "${inv.arguments[0]}->${inv.arguments[1]}"
                    null
                }
                else -> null
            }
        }
    private val manufacturers =
        mock(ManufacturerRepository::class.java) { inv -> if (inv.method.name == "findAll") listOf(leopard, sunsail, jeanneau) else null }
    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val catalogue =
        NauSysCatalogueSyncService(
            externalSystemService = systems,
            externalMappingService = mappings,
            countryRepository = any(),
            regionRepository = any(),
            regionAliasRepository = any(),
            locationRepository = any(),
            locationQueryingService = any(),
            manufacturerRepository = manufacturers,
            modelRepository = models,
            categoryRepository = any(),
            agencyRepository = any(),
            agencySourceRepository = any(),
            externalEquipmentRepository = any(),
            externalSeasonRepository = any(),
            externalBaseRepository = any(),
            yachtRepository = any(),
            manufacturerAliasResolver = ManufacturerAliasResolver(),
            modelNameNormaliser = ModelNameNormaliser(),
        )

    private fun sync(vararg models: RestYachtModel) = catalogue.modelsSync(RestYachtModelList(models = models.toList()))

    @AfterEach
    fun resetAliases() {
        BrandModelAliases.overrideForTests = null
    }

    @Test
    fun `G2 - an existing model keeps its manufacturer when NauSys names another one`() {
        modelMappings += ExternalMapping(3614L, leopard42.id, null, null, null)
        sync(RestYachtModel(id = 3614, name = "Leopard 42", yachtBuilderId = builderSunsail, loa = 12.8f))
        leopard42.manufacturer shouldBe leopard
        // the other fields still follow NauSys, as before
        leopard42.length shouldBe BigDecimal.valueOf(12.8f.toDouble())
    }

    @Test
    fun `G3 - an aliased charter-brand label is mapped to the builder model, never created, never overwritten`() {
        BrandModelAliases.overrideForTests =
            listOf(BrandModelAliases.Alias(ExternalSystemEnum.NAUSYS, "sunsail 424", 2234L, "Leopard 42"))
        sync(RestYachtModel(id = 3620, name = "Sunsail 424", yachtBuilderId = builderSunsail, loa = 13.0f, beam = 7.0f))
        savedMappings shouldContainExactly listOf("3620->2234")
        flushed.shouldBeEmpty()
        leopard42.manufacturer shouldBe leopard
        leopard42.length shouldBe BigDecimal("12.67")

        // the next run finds the mapping and still leaves the target alone
        modelMappings += ExternalMapping(3620L, leopard42.id, null, null, null)
        sync(RestYachtModel(id = 3620, name = "Sunsail 424", yachtBuilderId = builderSunsail, loa = 13.0f, beam = 7.0f))
        flushed.shouldBeEmpty()
        leopard42.length shouldBe BigDecimal("12.67")
    }

    @Test
    fun `G5 - a new model never gets a charter brand as manufacturer, a builder still is set`() {
        sync(
            RestYachtModel(id = 3630, name = "Sunsail 41", yachtBuilderId = builderSunsail),
            RestYachtModel(id = 3631, name = "Sun Odyssey 419", yachtBuilderId = builderJeanneau),
        )
        flushed.map { "${it.name}:${it.manufacturer?.name}" } shouldContainExactly listOf("Sunsail 41:null", "Sun Odyssey 419:Jeanneau")
    }
}
