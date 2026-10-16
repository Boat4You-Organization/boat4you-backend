package hr.workspace.boat4you.domains.catalouge.utils

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The partner's "Picture is not shown due to possible legal issues" placeholder (owner incident 16.10.2026). */
class KnownPlaceholderImagesTest {
    @Test
    fun `the blocked-photo URL marks a placeholder wherever it is hosted, a photo URL does not`() {
        val bytes = byteArrayOf(1, 2, 3)
        KnownPlaceholderImages.isPlaceholder("https://s3-eu-west-1.amazonaws.com/nausys/GenericImage/ORIGINAL/blockedPhoto.png", bytes) shouldBe true
        KnownPlaceholderImages.isPlaceholder("https://cdn.example/GENERICIMAGE/THUMB/BLOCKEDPHOTO.jpg", bytes) shouldBe true
        KnownPlaceholderImages.isPlaceholder("https://ws.nausys.com/CBMS-external/rest/yacht/50898648/pictures/n2.jpg", bytes) shouldBe false
        KnownPlaceholderImages.isPlaceholder("https://ws.nausys.com/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg", bytes) shouldBe false
        KnownPlaceholderImages.isPlaceholder(null, bytes) shouldBe false
    }

    @Test
    fun `sha256 is lowercase hex, and the known hashes are in that form`() {
        KnownPlaceholderImages.sha256("abc".toByteArray()) shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        (KnownPlaceholderImages.SOURCE_SHA256 + KnownPlaceholderImages.STORED_SHA256).forEach { it.matches(Regex("[0-9a-f]{64}")) shouldBe true }
        KnownPlaceholderImages.SOURCE_SHA256 shouldBe setOf("a9c83ba43cd4c9236c42c3ca2f0804d1deb65ca9cf778954e50edbe4c5661f25")
        KnownPlaceholderImages.STORED_SIZES shouldBe setOf(15_002L)
    }
}
