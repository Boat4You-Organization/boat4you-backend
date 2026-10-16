package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.exceptions.ImageNotFoundException
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImage
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Path
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * `/public/image/{id}` over two tables (V9_89): an id from 2,000,000,000 up is a catalogue_image row (our marina
 * photos), below it a partner yacht_image row - same file store, same resize gate. A missing or inactive catalogue
 * photo is a 404 like a missing partner image.
 */
class YachtImageCatalogueServingTests {
    private val yachtImages: YachtImageRepository = mock(YachtImageRepository::class.java)
    private val catalogueImages: CatalogueImageRepository = mock(CatalogueImageRepository::class.java)

    @TempDir
    lateinit var uploadDir: Path

    private fun service(): YachtImageService {
        File(uploadDir.toFile(), "marina-63").mkdirs()
        File(uploadDir.toFile(), "marina-63/a.webp").writeBytes(byteArrayOf(7, 7))
        File(uploadDir.toFile(), "y-1").mkdirs()
        File(uploadDir.toFile(), "y-1/b.webp").writeBytes(byteArrayOf(1, 2, 3))
        return YachtImageService(yachtImages, catalogueImages, uploadDir.toString(), 1, 100).also {
            it.resize = { path, _, _ -> File(path).readBytes() + 0.toByte() }
        }
    }

    private fun photo(
        id: Long,
        active: Boolean = true,
    ) = CatalogueImage().apply {
        this.id = id
        locationId = 63L
        url = "marina-63/a.webp"
        this.active = active
    }

    @Test
    fun `an id from FIRST_ID up is served from catalogue_image, with and without a width`() {
        val service = service()
        `when`(catalogueImages.findById(CatalogueImage.FIRST_ID)).thenReturn(Optional.of(photo(CatalogueImage.FIRST_ID)))

        assertContentEquals(byteArrayOf(7, 7), service.resizeImage(CatalogueImage.FIRST_ID, null, null).contentAsByteArray)
        assertContentEquals(byteArrayOf(7, 7, 0), service.resizeImage(CatalogueImage.FIRST_ID, 640, null).contentAsByteArray)
        verifyNoInteractions(yachtImages)
    }

    @Test
    fun `an id below FIRST_ID is a partner image, as before`() {
        val service = service()
        val last = CatalogueImage.FIRST_ID - 1
        `when`(yachtImages.findById(last)).thenReturn(Optional.of(YachtImage().apply { url = "y-1/b.webp" }))

        assertContentEquals(byteArrayOf(1, 2, 3), service.resizeImage(last, null, null).contentAsByteArray)
        verifyNoInteractions(catalogueImages)
    }

    @Test
    fun `a missing or inactive catalogue photo is a 404, never a partner image of that id`() {
        val service = service()
        val inactive = CatalogueImage.FIRST_ID + 1
        `when`(catalogueImages.findById(inactive)).thenReturn(Optional.of(photo(inactive, active = false)))
        `when`(catalogueImages.findById(CatalogueImage.FIRST_ID + 2)).thenReturn(Optional.empty())

        assertFailsWith<ImageNotFoundException> { service.resizeImage(inactive, null, null) }
        assertFailsWith<ImageNotFoundException> { service.resizeImage(CatalogueImage.FIRST_ID + 2, 640, null) }
        verifyNoInteractions(yachtImages)
    }

    @Test
    fun `a catalogue row whose file is gone is a 404`() {
        val service = service()
        val id = CatalogueImage.FIRST_ID + 3
        `when`(catalogueImages.findById(id)).thenReturn(Optional.of(photo(id).apply { url = "marina-63/gone.webp" }))

        assertFailsWith<ImageNotFoundException> { service.resizeImage(id, null, null) }
    }
}
