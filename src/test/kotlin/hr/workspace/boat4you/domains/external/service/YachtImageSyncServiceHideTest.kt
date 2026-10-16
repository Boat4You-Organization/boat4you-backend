package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import hr.workspace.boat4you.domains.catalouge.utils.KnownPlaceholderImages
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Types
import java.util.Optional

/**
 * The image download stores the partner's placeholder hidden (V9_91, owner incident 16.10.2026, FuFu 4345): synced, so
 * it is never fetched again, never the main image - and when it was the boat's card photo, the card gets the photo the
 * sync rule picks right away.
 */
class YachtImageSyncServiceHideTest {
    private val repository: YachtImageRepository = mock(YachtImageRepository::class.java)
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val service = YachtImageSyncService(repository, jdbc)

    private val model = "https://ws.nausys.com/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg"
    private val pictures = "https://ws.nausys.com/CBMS-external/rest/yacht/50898648/pictures/"

    private fun boat(vararg images: YachtImage) =
        Yacht().apply {
            id = 4345
            mainImageId = 341899
            images.forEach { it.yacht = this }
            yachtImages.addAll(images)
        }

    private val placeholder =
        YachtImage().apply {
            id = 341899
            externalUrl = model
            position = 0
            mainImage = true
            synced = false
        }
    private val layout =
        YachtImage().apply {
            id = 75723
            externalUrl = pictures + "layout.png"
            url = "y-4345/l.webp"
            position = 1
        }
    private val n2 =
        YachtImage().apply {
            id = 341900
            externalUrl = pictures + "n2.jpg"
            url = "y-4345/n2.webp"
            position = 2
        }

    @Test
    fun `a placeholder is stored hidden, loses the main flag, and the card photo moves to the first photo`() {
        boat(placeholder, layout, n2)
        `when`(repository.findById(341899)).thenReturn(Optional.of(placeholder))

        service.updateYachtImageWithUrl(341899, "y-4345/p.webp", KnownPlaceholderImages.PARTNER_BLOCKED_PHOTO)

        verify(jdbc).update(
            "UPDATE yacht_image SET hidden = true, hidden_reason = ?, main_image = false WHERE id = ?",
            KnownPlaceholderImages.PARTNER_BLOCKED_PHOTO,
            341899L,
        )
        verify(jdbc).update(
            eq("UPDATE yacht SET main_image_id = ? WHERE id = ? AND main_image_id = ?"),
            aryEq(arrayOf<Any?>(341900L, 4345L, 341899L)),
            aryEq(intArrayOf(Types.BIGINT, Types.BIGINT, Types.BIGINT)),
        )
        placeholder.url shouldBe "y-4345/p.webp"
        placeholder.synced shouldBe true
        placeholder.mainImage shouldBe false
        placeholder.hidden shouldBe true
        verify(repository).save(placeholder)
    }

    @Test
    fun `a placeholder that is not the card photo leaves the card alone`() {
        boat(placeholder, n2).mainImageId = 341900
        `when`(repository.findById(341899)).thenReturn(Optional.of(placeholder))

        service.updateYachtImageWithUrl(341899, "y-4345/p.webp", KnownPlaceholderImages.PARTNER_BLOCKED_PHOTO)

        verify(jdbc).update(
            "UPDATE yacht_image SET hidden = true, hidden_reason = ?, main_image = false WHERE id = ?",
            KnownPlaceholderImages.PARTNER_BLOCKED_PHOTO,
            341899L,
        )
        verifyNoMoreInteractions(jdbc)
    }

    @Test
    fun `a photo is stored as before - no SQL besides the entity save`() {
        boat(n2)
        n2.url = null
        `when`(repository.findById(341900)).thenReturn(Optional.of(n2))

        service.updateYachtImageWithUrl(341900, "y-4345/n2.webp")

        n2.url shouldBe "y-4345/n2.webp"
        n2.synced shouldBe true
        n2.hidden shouldBe false
        verifyNoInteractions(jdbc)
        verify(repository).save(n2)
    }
}
