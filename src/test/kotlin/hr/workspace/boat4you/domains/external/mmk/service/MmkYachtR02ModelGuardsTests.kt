package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.ModelNameNormaliser
import hr.workspace.boat4you.domains.catalouge.services.ModelQueryingService
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.Optional

/**
 * R02 (14.10.2026) in the MMK yacht sync (the names: YachtNamePolicyTest.applyTo): a new MMK model id with a charter-brand
 * label lands on the builder model (G3, partner/brand_model_alias.txt) and is never created; a new model never gets a
 * charter brand as its manufacturer (G5). Repositories are answer-by-method-name mocks.
 */
class MmkYachtR02ModelGuardsTests {
    private fun manufacturer(
        id: Long,
        name: String,
    ): Manufacturer {
        val m = Manufacturer()
        m.id = id
        m.name = name
        return m
    }

    private val leopard42 =
        Model().apply {
            id = 2234L
            name = "Leopard 42"
            manufacturer = manufacturer(1061L, "Leopard Catamarans")
        }
    private val savedMappings = mutableListOf<String>()
    private val savedModels = mutableListOf<Model>()
    private var targetName = "Leopard 42"

    private val sunsailShipyard = 77L
    private val jeanneauShipyard = 78L

    private val models =
        mock(ModelRepository::class.java) { inv ->
            when (inv.method.name) {
                "findById" -> Optional.ofNullable(leopard42.takeIf { inv.arguments[0] == 2234L }?.also { it.name = targetName })
                "save" ->
                    inv.getArgument<Model>(0).also {
                        it.id = 9000L
                        savedModels += it
                    }
                else -> null
            }
        }
    private val mappings =
        mock(ExternalMappingService::class.java) { inv ->
            when (inv.method.name) {
                "saveMapping" -> {
                    savedMappings += "${inv.arguments[0]}->${inv.arguments[1]}"
                    null
                }
                "getCachedAllMappingsByType" ->
                    listOf(ExternalMapping(sunsailShipyard, 561L, null, null, null), ExternalMapping(jeanneauShipyard, 5L, null, null, null))
                else -> null
            }
        }
    private val manufacturers =
        mock(ManufacturerRepository::class.java) { inv ->
            when {
                inv.method.name == "findById" && inv.arguments[0] == 561L -> Optional.of(manufacturer(561L, "Sunsail"))
                inv.method.name == "findById" && inv.arguments[0] == 5L -> Optional.of(manufacturer(5L, "Jeanneau"))
                inv.method.name == "findById" -> Optional.empty<Manufacturer>()
                else -> null
            }
        }
    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }

    // no model is known by partner id nor by name: the create path
    private val modelQueries = mock(ModelQueryingService::class.java)

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        MmkYachtSyncService(
            yachtRepository = any(),
            externalMappingRepository = any(),
            externalSystemService = systems,
            modelRepository = models,
            externalMappingService = mappings,
            yachtImageRepository = any(),
            manufacturerRepository = manufacturers,
            locationQueryingService = any(),
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentLinkResolver = any(),
            modelQueryingService = modelQueries,
            externalEquipmentRepository = any(),
            extraRepository = any(),
            yachtExtraRepository = any(),
            fileSystemService = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            agencyRepository = any(),
            modelNameNormaliser = ModelNameNormaliser(),
            realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(),
        )

    private fun mmkYacht(
        modelId: Long,
        model: String,
        shipyardId: Long? = null,
    ) = org.openapitools.client.mmk.model.Yacht(
        id = 1L,
        name = "Sunsail 424/4/4 Premium",
        kind = "Catamaran",
        homeBaseId = 1L,
        homeBase = "Base",
        companyId = 1L,
        company = "Company",
        model = model,
        modelId = modelId,
        shipyardId = shipyardId,
    )

    @Test
    fun `G3 - a new MMK model id labelled Sunsail 424 is mapped to Leopard 42 and never created`() {
        val model = service.syncAndGetModel(mmkYacht(4242L, "Sunsail 424"))
        model?.id shouldBe 2234L
        model?.name shouldBe "Leopard 42"
        model?.manufacturer?.name shouldBe "Leopard Catamarans"
        savedMappings shouldContainExactly listOf("4242->2234")
        savedModels.shouldBeEmpty()
    }

    @Test
    fun `G3 - an alias whose target row was renamed is ignored, the label is created as the partner sends it`() {
        targetName = "Leopard 45"
        val model = service.syncAndGetModel(mmkYacht(4242L, "Sunsail 424"))
        model?.name shouldBe "Sunsail 424"
        savedModels.map { it.name } shouldContainExactly listOf("Sunsail 424")
        savedMappings shouldContainExactly listOf("4242->9000")
    }

    @Test
    fun `G5 - a charter brand is never the manufacturer of a new model, a builder is`() {
        service.syncAndGetModel(mmkYacht(1L, "Sunsail 41", sunsailShipyard))?.manufacturer shouldBe null
        service.syncAndGetModel(mmkYacht(2L, "Sun Odyssey 410", jeanneauShipyard))?.manufacturer?.name shouldBe "Jeanneau"
    }
}
