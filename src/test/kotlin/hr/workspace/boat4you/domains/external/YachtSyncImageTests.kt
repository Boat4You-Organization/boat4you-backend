package hr.workspace.boat4you.domains.external

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.enums.YachtImageSource
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
import org.mockito.Mockito.clearInvocations
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

    /** V9_92: our own photo, uploaded by an admin (no external_url). */
    private fun own(
        id: Long,
        position: Int,
        main: Boolean = false,
    ) = YachtImage().apply {
        this.id = id
        url = "y-12663/own-$id.webp"
        this.position = position.toShort()
        mainImage = main
        synced = true
        source = YachtImageSource.OWN
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
        clearInvocations(images)
        yacht.mainImageId = MainImageRules.applyAfterSync(yacht, images::save) // the sync's own call after syncPictures

        yacht.mainImageId shouldBe 341900L
        listOf(blocked, layout, n2).map { it.mainImage } shouldContainExactly listOf(false, false, true) // ... and taken back
        verify(images).save(blocked)
        verify(images).save(n2)
        verifyNoMoreInteractions(images) // only the flags that changed are written
        blocked.hidden shouldBe true // the sync never writes it (read-only for Hibernate)
        yacht.yachtImages.map { it.id } shouldContainExactly listOf(341899L, 75723L, 341900L) // the row stays: never fetched again
    }

    @Test
    fun `MMK - a hidden Main image loses the flag to the first photo, only hidden images left - no card photo, no main flag`() {
        val hidden = image(1, mmkDocs + "a.jpg", 0, main = true).apply { hidden = true }
        val photo = image(2, mmkDocs + "b.jpg", 1)
        val boat = yacht(hidden, photo).apply { mainImageId = 1 }
        mmk.syncPictures(
            listOf(Image(url = mmkDocs + "a.jpg", description = "Main image", sortOrder = 0), Image(url = mmkDocs + "b.jpg", sortOrder = 1)),
            isNewEntity = false,
            yacht = boat,
        )
        boat.mainImageId = MainImageRules.applyAfterSync(boat, images::save) // the sync's own call after syncPictures
        boat.mainImageId shouldBe 2L
        listOf(hidden, photo).map { it.mainImage } shouldContainExactly listOf(false, true)

        val alone = image(3, mmkDocs + "c.jpg", 0, main = true).apply { this.hidden = true }
        MainImageRules.applyAfterSync(yacht(alone), images::save) shouldBe null
        alone.mainImage shouldBe false
    }

    @Test
    fun `NauSys syncPictures - our own photos stay first, are never deleted or re-flagged, the OWN main survives the partner's mainPicture`() {
        // owner request 17.10.2026, gulet Allure 11769: our photos on a partner boat
        val ownMain = own(900, 0, main = true)
        val ownSecond = own(901, 1)
        val partnerMain = image(1, nausysPics + "main.jpg", 2)
        val dropped = image(2, nausysPics + "n1.jpg", 3)
        val yacht = yacht(partnerMain, ownSecond, dropped, ownMain).apply { mainImageId = 900 }
        nausys.syncPictures(
            listOf(RestYachtPicture(src = nausysPics + "n2.jpg", mainPicture = false), RestYachtPicture(src = nausysPics + "main.jpg", mainPicture = true)),
            isNewEntity = false,
            yacht = yacht,
        )

        verify(files).deleteFile("y-12663/2.webp") // the partner's dropped picture only
        verifyNoMoreInteractions(files)
        val added = yacht.yachtImages.single { it.id == null }
        added.externalUrl shouldBe nausysPics + "n2.jpg"
        yacht.yachtImages.sortedBy { it.position }.map { it.id } shouldContainExactly listOf(900L, 901L, null, 1L) // OWN block, then the partner's order
        listOf(ownMain, ownSecond).map { it.position?.toInt() } shouldContainExactly listOf(0, 1)
        partnerMain.mainImage shouldBe true // the partner's flag, re-applied ...

        yacht.mainImageId = MainImageRules.applyAfterSync(yacht, images::save)
        yacht.mainImageId shouldBe 900L // ... never over the admin's own main
        listOf(ownMain, ownSecond, partnerMain).map { it.mainImage } shouldContainExactly listOf(true, false, false)
        listOf(ownMain, ownSecond).map { it.source } shouldContainExactly listOf(YachtImageSource.OWN, YachtImageSource.OWN)
    }

    @Test
    fun `MMK syncPictures - our own photos stay first and are never deleted, the partner's follow them with and without sortOrder`() {
        val ownMain = own(900, 0, main = true)
        val ownSecond = own(901, 1)
        val a = image(1, mmkDocs + "a.jpg", 0, main = true)
        val b = image(2, mmkDocs + "b.jpg", 1)
        val dropped = image(3, mmkDocs + "c.jpg", 2)
        val boat = yacht(a, b, dropped, ownSecond, ownMain).apply { mainImageId = 900 }
        mmk.syncPictures(
            listOf(Image(url = mmkDocs + "a.jpg", description = "Main image", sortOrder = 0), Image(url = mmkDocs + "b.jpg", sortOrder = 1)),
            isNewEntity = false,
            yacht = boat,
        )

        verify(files).deleteFile("y-12663/3.webp")
        verifyNoMoreInteractions(files)
        boat.yachtImages.sortedBy { it.position }.map { it.id } shouldContainExactly listOf(900L, 901L, 1L, 2L)
        boat.mainImageId = MainImageRules.applyAfterSync(boat, images::save)
        boat.mainImageId shouldBe 900L
        listOf(ownMain, a, b).map { it.mainImage } shouldContainExactly listOf(true, false, false)

        // the partner sends no order (all sortOrder equal): its pictures still go behind our photos, in its order
        mmk.syncPictures(listOf(Image(url = mmkDocs + "b.jpg", sortOrder = 0), Image(url = mmkDocs + "a.jpg", sortOrder = 0)), isNewEntity = false, yacht = boat)
        boat.yachtImages.sortedBy { it.position }.map { it.id } shouldContainExactly listOf(900L, 901L, 2L, 1L)
        listOf(ownMain, ownSecond).map { it.position?.toInt() } shouldContainExactly listOf(0, 1)
    }

    @Test
    fun `MMK syncPictures - a new picture without sortOrder among ordered ones goes behind our own photos, never NULL`() {
        // review 17.10.2026: a NULL position sorts FIRST on the public gallery (toPublicDtos), before our own photos
        val ownMain = own(900, 0, main = true)
        val ownSecond = own(901, 1)
        val a = image(1, mmkDocs + "a.jpg", 2, main = true)
        val boat = yacht(a, ownSecond, ownMain).apply { mainImageId = 900 }
        mmk.syncPictures(
            listOf(
                Image(url = mmkDocs + "a.jpg", description = "Main image", sortOrder = 0),
                Image(url = mmkDocs + "b.jpg"),
                Image(url = mmkDocs + "c.jpg", sortOrder = 5),
            ),
            isNewEntity = false,
            yacht = boat,
        )

        val added = boat.yachtImages.filter { it.id == null }.associateBy { it.externalUrl }
        added.getValue(mmkDocs + "b.jpg").position?.toInt() shouldBe 3 // its index (1) behind our 2 photos
        added.getValue(mmkDocs + "c.jpg").position?.toInt() shouldBe 7 // the partner's sortOrder behind our 2 photos
        listOf(ownMain, ownSecond, a).map { it.position?.toInt() } shouldContainExactly listOf(0, 1, 2)
    }

    @Test
    fun `without own photos both syncs place the partner's pictures exactly as before`() {
        val a = image(1, mmkDocs + "a.jpg", 5, main = true)
        val boat = yacht(a)
        mmk.syncPictures(listOf(Image(url = mmkDocs + "a.jpg", sortOrder = 0)), isNewEntity = false, yacht = boat)
        a.position?.toInt() shouldBe 5 // MMK without a partner order never moves a picture

        val n = image(2, nausysPics + "main.jpg", 3)
        val nausysBoat = yacht(n)
        nausys.syncPictures(listOf(RestYachtPicture(src = nausysPics + "main.jpg", mainPicture = true)), isNewEntity = false, yacht = nausysBoat)
        n.position?.toInt() shouldBe 0 // NauSys: the partner's index
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
