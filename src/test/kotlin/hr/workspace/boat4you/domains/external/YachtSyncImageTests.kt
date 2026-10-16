package hr.workspace.boat4you.domains.external

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import hr.workspace.boat4you.domains.catalouge.utils.MainImageRules
import hr.workspace.boat4you.domains.external.job.ImageDownloadJob
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtSyncService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtSyncService
import hr.workspace.boat4you.domains.external.service.YachtImageIntegrationService
import hr.workspace.boat4you.domains.external.service.YachtImageIntegrationServiceAsync
import hr.workspace.boat4you.domains.external.service.YachtImageSyncService
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.openapitools.client.mmk.model.Image
import org.openapitools.client.nausys.model.RestYachtPicture

/**
 * The partner syncs and catalogue photos (design 16.10.2026).
 * 1. getMainImage never makes the layout drawing the card photo while the boat has a photo (MMK + NauSys).
 * 2. Marina photos live in catalogue_image, so a sync cannot delete them: syncPictures deletes only yacht_image rows of
 *    the boat - and their own files - that the partner no longer lists, and no sync / image-job class can even reach
 *    catalogue_image (no CatalogueImageRepository among their dependencies).
 */
class YachtSyncImageTests {
    private inline fun <reified T> any(): T = mock(T::class.java)

    private val files: FileSystemService = mock(FileSystemService::class.java)
    private val images: YachtImageRepository = mock(YachtImageRepository::class.java)

    private val mmk =
        MmkYachtSyncService(
            yachtRepository = any(),
            externalMappingRepository = any(),
            externalSystemService = any(),
            modelRepository = any(),
            externalMappingService = any(),
            yachtImageRepository = images,
            manufacturerRepository = any(),
            locationQueryingService = any(),
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentLinkResolver = any(),
            modelQueryingService = any(),
            externalEquipmentRepository = any(),
            extraRepository = any(),
            yachtExtraRepository = any(),
            fileSystemService = files,
            languageRepository = any(),
            yachtTranslationRepository = any(),
            agencyRepository = any(),
            modelNameNormaliser = any(),
            realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(),
        )

    private val nausys =
        NauSysYachtSyncService(
            yachtRepository = any(),
            externalMappingRepository = any(),
            externalSystemService = any(),
            modelRepository = any(),
            externalMappingService = any(),
            yachtImageRepository = images,
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentLinkResolver = any(),
            locationQueryingService = any(),
            externalEquipmentRepository = any(),
            yachtExtraRepository = any(),
            extraRepository = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            fileSystemService = files,
            agencyRepository = any(),
            externalSeasonRepository = any(),
            realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(),
        )

    private fun yacht(vararg images: YachtImage) =
        Yacht().apply {
            id = 12663
            images.forEach { it.yacht = this }
            yachtImages.addAll(images)
        }

    private fun image(
        id: Long,
        externalUrl: String,
        position: Int,
        main: Boolean = false,
    ) = YachtImage().apply {
        this.id = id
        this.externalUrl = externalUrl
        url = "y-12663/$id.webp"
        this.position = position.toShort()
        mainImage = main
        synced = true
    }

    private val mmkDocs = "https://www.booking-manager.com/cbm/documents/"
    private val nausysPics = "https://ws.nausys.com/CBMS-external/rest/yacht/103152/pictures/"

    @Test
    fun `MMK - a layout-named Main image is not the card photo when the boat has photos`() {
        val plan = image(218650, mmkDocs + "36397280967800260_1695203065343_L55_layout_5.jpg", 0, main = true)
        val side = image(218651, mmkDocs + "36397340967800260_L55_vistalato.jpg", 1)
        val deck = image(218652, mmkDocs + "37114131586300260_themoon_2071.jpg", 2)
        mmk.getMainImage(yacht(plan, deck, side)) shouldBe side
        mmk.getMainImage(yacht(plan)) shouldBe plan // only the plan: unchanged behaviour
    }

    @Test
    fun `NauSys - a layout flagged mainPicture is not the card photo when the boat has photos`() {
        val layout = image(301, nausysPics + "layout.jpg", 0, main = true)
        val main = image(302, nausysPics + "main.jpg", 1)
        val n1 = image(303, nausysPics + "n1.jpg", 2)
        nausys.getMainImage(yacht(n1, layout, main)) shouldBe main
        nausys.getMainImage(yacht(n1, layout, main).apply { mainImageId = 303 }) shouldBe n1 // the current photo stays
    }

    @Test
    fun `MMK syncPictures deletes only the boat's own dropped yacht_image file`() {
        val kept = image(1, mmkDocs + "a.jpg", 0, main = true)
        val dropped = image(2, mmkDocs + "b.jpg", 1)
        val yacht = yacht(kept, dropped)
        mmk.syncPictures(listOf(Image(url = mmkDocs + "a.jpg", description = "Main image", sortOrder = 0)), isNewEntity = false, yacht = yacht)

        verify(files).deleteFile("y-12663/2.webp")
        verifyNoMoreInteractions(files)
        yacht.yachtImages.map { it.id } shouldContainExactly listOf(1L)
    }

    @Test
    fun `NauSys syncPictures deletes only the boat's own dropped yacht_image file`() {
        val kept = image(1, nausysPics + "main.jpg", 0, main = true)
        val dropped = image(2, nausysPics + "n1.jpg", 1)
        val yacht = yacht(kept, dropped)
        nausys.syncPictures(listOf(RestYachtPicture(src = nausysPics + "main.jpg", mainPicture = true)), isNewEntity = false, yacht = yacht)

        verify(files).deleteFile("y-12663/2.webp")
        verifyNoMoreInteractions(files)
        yacht.yachtImages.map { it.id } shouldContainExactly listOf(1L)
    }

    @Test
    fun `NauSys - the hidden model placeholder flagged mainPicture is never the main, the card photo's flag is the only one`() {
        // owner incident 16.10.2026, FuFu 4345: the partner flags its MODEL picture, the blocked-photo placeholder
        val model = "https://ws.nausys.com/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg"
        val blocked = image(341899, model, 0, main = true).apply { hidden = true }
        val layout = image(75723, nausysPics + "layout.png", 1)
        val n2 = image(341900, nausysPics + "n2.jpg", 2)
        val yacht = yacht(blocked, layout, n2).apply { mainImageId = 341899 }
        nausys.syncPictures(
            listOf(
                RestYachtPicture(src = model, mainPicture = true),
                RestYachtPicture(src = nausysPics + "layout.png", mainPicture = false),
                RestYachtPicture(src = nausysPics + "n2.jpg", mainPicture = false),
            ),
            isNewEntity = false,
            yacht = yacht,
        )
        blocked.mainImage shouldBe true // the partner's flag, re-applied by the sync ...
        yacht.mainImageId = nausys.getMainImage(yacht)?.id
        MainImageRules.flagMain(yacht.yachtImages, yacht.mainImageId)

        yacht.mainImageId shouldBe 341900L
        listOf(blocked, layout, n2).map { it.mainImage } shouldContainExactly listOf(false, false, true) // ... and taken back
        blocked.hidden shouldBe true // the sync never writes it (read-only for Hibernate)
        yacht.yachtImages.map { it.id } shouldContainExactly listOf(341899L, 75723L, 341900L) // the row stays: never fetched again
    }

    @Test
    fun `MMK - only hidden images left - no card photo, no main flag`() {
        val hidden = image(1, mmkDocs + "a.jpg", 0, main = true).apply { hidden = true }
        mmk.getMainImage(yacht(hidden)) shouldBe null
    }

    @Test
    fun `no partner sync or image job can reach catalogue_image`() {
        listOf(
            MmkYachtSyncService::class.java,
            NauSysYachtSyncService::class.java,
            ImageDownloadJob::class.java,
            YachtImageIntegrationService::class.java,
            YachtImageIntegrationServiceAsync::class.java,
            YachtImageSyncService::class.java,
        ).forEach { cls ->
            val dependencies = cls.declaredConstructors.flatMap { it.parameterTypes.toList() } + cls.declaredFields.map { it.type }
            (CatalogueImageRepository::class.java in dependencies) shouldBe false
        }
    }
}
