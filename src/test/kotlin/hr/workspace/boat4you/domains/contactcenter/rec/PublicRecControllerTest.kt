package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.controllers.PublicRecController
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * POST /public/cc/v1/r (P6 R1.3): the recorder's payloads are streamed into the spool as received, never parsed on cusma2. The
 * Origin decides the site, the site's switch and the quota flag decide whether anything is written.
 */
class PublicRecControllerTest {
    @TempDir lateinit var root: Path

    private val switches = mutableMapOf("boat4you" to true, "catamaran-greece" to false)
    private val settings =
        mock(RecSettingsStore::class.java) { inv ->
            if (inv.method.name == "forSite") {
                val site = inv.arguments[0] as String
                RecSiteSetting(site, switches[site] == true, 100, 25)
            } else {
                null
            }
        }

    private fun controller() = PublicRecController(settings, RecSpool(root.toString()))

    private fun post(
        body: ByteArray,
        origin: String? = "https://www.boat4you.com",
    ) = controller().ingest(
        MockHttpServletRequest("POST", "/public/cc/v1/r").apply {
            origin?.let { addHeader("Origin", it) }
            setContent(body)
        },
    )

    private fun spooled(): List<Path> =
        if (Files.exists(root.resolve("spool"))) Files.walk(root.resolve("spool")).use { s -> s.filter(Files::isRegularFile).toList() } else emptyList()

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    @Test
    fun `gzip and plain bodies are spooled as received, one file each, in the minute folder`() {
        val gz = gzip("""{"e":["0.8.73",1],"a":[]}""")
        post(gz).statusCode.value() shouldBe 204
        post(""" {"e":["0.8.73",2],"a":[]}""".toByteArray()).statusCode.value() shouldBe 204
        val files = spooled().sortedBy { it.name.substringAfterLast('.') }
        files shouldHaveSize 2
        files[0].name shouldEndWith ".gz"
        files[0].readBytes().toList() shouldBe gz.toList()
        files[1].name shouldMatch Regex("""^\d{13}-boat4you-[0-9a-f-]{36}\.json$""")
        files[1].parent.name shouldMatch Regex("""^\d{12}$""")
    }

    @Test
    fun `a foreign origin is 403 and writes nothing`() {
        post("{}".toByteArray(), origin = "https://evil.example").statusCode.value() shouldBe 403
        post("{}".toByteArray(), origin = null).statusCode.value() shouldBe 403
        spooled().shouldBeEmpty()
    }

    @Test
    fun `a site that is switched off is 403`() {
        post("{}".toByteArray(), origin = "https://www.catamaran-charter-greece.com").let {
            it.statusCode.value() shouldBe 403
            (it.body as Map<*, *>)["error"] shouldBe "off"
        }
        spooled().shouldBeEmpty()
    }

    @Test
    fun `over quota is 507 and nothing is written`() {
        Files.writeString(root.resolve(RecSpool.QUOTA_FLAG), "45 GB")
        post("{}".toByteArray()).statusCode.value() shouldBe 507
        spooled().shouldBeEmpty()
    }

    @Test
    fun `a body over 1 MiB is 413, one that is neither gzip nor JSON is 400`() {
        post(ByteArray(PublicRecController.MAX_BODY_BYTES + 1) { '{'.code.toByte() }).statusCode.value() shouldBe 413
        post("hello".toByteArray()).statusCode.value() shouldBe 400
        post(ByteArray(0)).statusCode.value() shouldBe 400
        spooled().shouldBeEmpty()
    }

    @Test
    fun `rec-config gives the site's switch and shares, cached for five minutes, an unknown site is 400`() {
        controller().config("boat4you").let {
            it.statusCode.value() shouldBe 200
            it.headers.cacheControl shouldBe "max-age=300, public"
            it.body shouldBe mapOf("enabled" to true, "sample" to 100, "mobileSample" to 25)
        }
        controller().config("evil").statusCode.value() shouldBe 400
        controller().config(null).statusCode.value() shouldBe 400
    }
}
