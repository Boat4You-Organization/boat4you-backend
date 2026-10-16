package hr.workspace.boat4you.domains.external.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.common.services.ImageService
import hr.workspace.boat4you.domains.catalouge.utils.KnownPlaceholderImages
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.ArgumentMatchers.startsWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * The image job's own path from the partner URL to the row (owner incident 16.10.2026, FuFu 4345): the real download
 * (redirects followed, a local HTTP server stands in for the partner), the real WebP conversion and file save, and the
 * row update - a picture that ends at the partner's blocked photo is stored hidden (PARTNER_BLOCKED_PHOTO), a photo as
 * before (no reason).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtImageIntegrationServiceAsyncPlaceholderTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val uploadDir = Files.createTempDirectory("image-job-uploads")
    private val rows: YachtImageSyncService = mock(YachtImageSyncService::class.java)

    private val transactions =
        TransactionTemplate(
            object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

                override fun commit(status: TransactionStatus) = Unit

                override fun rollback(status: TransactionStatus) = Unit
            },
        )

    private val service by lazy {
        YachtImageIntegrationServiceAsync(
            imageService = ImageService(),
            fileSystemService = FileSystemService().also { ReflectionTestUtils.setField(it, "uploadDir", uploadDir.toString()) },
            transactionTemplate = transactions,
            yachtImageSyncService = rows,
        )
    }

    private fun png(mat: Mat): ByteArray {
        val buffer = MatOfByte()
        Imgcodecs.imencode(".png", mat, buffer)
        return buffer.toArray()
    }

    @BeforeAll
    fun start() {
        nu.pattern.OpenCV.loadLocally()
        // any decodable picture stands in for the blocked photo: the final URL alone marks it
        val blocked = png(Mat(131, 193, CvType.CV_8UC3, Scalar(200.0, 200.0, 200.0)))
        val photo = png(Mat(120, 160, CvType.CV_8UC3).also { Core.randu(it, 0.0, 255.0) })
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg") { exchange ->
            exchange.responseHeaders.add("Location", "$base/nausys/GenericImage/ORIGINAL/blockedPhoto.png")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/nausys/GenericImage/ORIGINAL/blockedPhoto.png") { ok(it, blocked) }
        server.createContext("/CBMS-external/rest/yacht/50898648/pictures/n2.jpg") { ok(it, photo) }
        server.start()
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun ok(
        exchange: HttpExchange,
        body: ByteArray,
    ) {
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    @Test
    fun `a model picture redirected to the blocked photo is stored, synced and hidden`() {
        service.syncOffersForAgencyYachts(341899, 4345, "$base/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg").get() shouldBe
            ImageSyncOutcome.SUCCESS

        verify(rows).updateYachtImageWithUrl(eq(341899L), startsWith("y-4345/"), eq(KnownPlaceholderImages.PARTNER_BLOCKED_PHOTO))
    }

    @Test
    fun `a photo is stored as before, without a reason`() {
        service.syncOffersForAgencyYachts(341900, 4345, "$base/CBMS-external/rest/yacht/50898648/pictures/n2.jpg").get() shouldBe
            ImageSyncOutcome.SUCCESS

        verify(rows).updateYachtImageWithUrl(eq(341900L), startsWith("y-4345/"), isNull())
    }
}
