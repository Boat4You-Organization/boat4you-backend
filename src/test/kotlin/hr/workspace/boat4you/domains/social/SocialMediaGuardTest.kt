package hr.workspace.boat4you.domains.social

import hr.workspace.boat4you.domains.social.jpa.SocialMediaRepository
import hr.workspace.boat4you.domains.social.service.SocialConfigException
import hr.workspace.boat4you.domains.social.service.SocialImages
import hr.workspace.boat4you.domains.social.service.SocialRules
import hr.workspace.boat4you.domains.social.service.SocialMediaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO

/** The media store refuses to work outside the shared directory, and reads photo sizes from the header before decoding. */
class SocialMediaGuardTest {
    private val repo =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SocialMediaRepository::class.java)) { _, m, _ ->
            error("unexpected ${m.name}")
        } as SocialMediaRepository

    private fun temp(
        name: String,
        bytes: ByteArray,
    ): Path = Files.createTempFile("social-", name).also { Files.write(it, bytes) }

    @Test
    fun `without the marker file nothing is stored or served, and the reason is reported`() {
        val dir = Files.createTempDirectory("social-media")
        val store = SocialMediaStore(TestProps.props(mediaDir = dir.toString()), repo)
        store.unavailableReason()!! shouldContain ".objave-shared"
        shouldThrow<SocialConfigException> { store.store("x".byteInputStream(), "a.jpg", null) }
        shouldThrow<SocialConfigException> { store.requireShared() }
        Files.createFile(dir.resolve(SocialMediaStore.SHARED_MARKER))
        store.unavailableReason().shouldBeNull()
        // Never created by the code: a missing share stays visible.
        SocialMediaStore(TestProps.props(mediaDir = dir.resolve("not-mounted").toString()), repo).unavailableReason()!! shouldContain "not the shared directory"
        Files.exists(dir.resolve("not-mounted")) shouldBe false
    }

    @Test
    fun `a PNG that claims a huge canvas is measured from its header, not decoded`() {
        val ihdr = ByteBuffer.allocate(17).put("IHDR".toByteArray()).putInt(20_000).putInt(20_000).put(byteArrayOf(8, 6, 0, 0, 0)).array()
        val crc = CRC32().apply { update(ihdr) }.value.toInt()
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val png = signature + ByteBuffer.allocate(4).putInt(13).array() + ihdr + ByteBuffer.allocate(4).putInt(crc).array()
        SocialImages.headerSize(temp(".png", png)) shouldBe (20_000 to 20_000)
    }

    @Test
    fun `JPEG and WebP sizes come from the header`() {
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(321, 123, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        SocialImages.headerSize(temp(".jpg", jpeg)) shouldBe (321 to 123)
        // VP8X: canvas width - 1 and height - 1 as 24-bit little endian at bytes 24 and 27.
        val vp8x = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + ByteArray(8) + byteArrayOf(0x3F, 0x9C.toByte(), 0x00, 0x1F, 0x4E, 0x00)
        SocialImages.webpSize(vp8x) shouldBe (40_000 to 20_000)
        SocialImages.webpSize("RIFF....WEBPVP8 ".toByteArray()).shouldBeNull()
    }

    @Test
    fun `catalogue photos are centre-cropped into Instagram's 4_5 to 1_91 range, other photos are kept whole`() {
        fun jpeg(
            w: Int,
            h: Int,
        ) = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        // 2160×1102 (1.96:1) is the photo that kept the AI post of 10.11.2026 from Instagram.
        val wide = SocialImages.normalize(jpeg(2160, 1102), fitInstagram = true)!!
        SocialRules.instagramRatioOk(wide.width, wide.height) shouldBe true
        wide.height shouldBe 1102
        val tall = SocialImages.normalize(jpeg(1000, 2000), fitInstagram = true)!!
        SocialRules.instagramRatioOk(tall.width, tall.height) shouldBe true
        tall.width shouldBe 1000
        SocialImages.normalize(jpeg(1600, 1000), fitInstagram = true)!!.let { (it.width to it.height) shouldBe (1600 to 1000) }
        SocialImages.normalize(jpeg(2160, 1102))!!.let { (it.width to it.height) shouldBe (2160 to 1102) }
        SocialImages.instagramCrop(4000, 1000)!!.let { (it.x to it.width) shouldBe (1045 to 1910) }
    }
}
