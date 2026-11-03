package hr.workspace.boat4you.domains.contactcenter.rec

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reading recordings and archived assets on cusma2 (P6 R2): through the hard NFS share of cusma3, so at most a few reads wait
 * at once and the next is refused at once; nothing outside the recordings folder is ever read.
 */
class RecFilesTest {
    @TempDir lateinit var root: Path

    private val files by lazy { RecFiles(root.toString()) }

    @Test
    fun `a file under the root is read, a missing one or one outside the root is null`() {
        Files.createDirectories(root.resolve("boat4you/2026-11-01"))
        Files.writeString(root.resolve("boat4you/2026-11-01/a.ndjson.gz"), "abc")
        Files.writeString(root.parent.resolve("secret.txt"), "no")
        files.read("boat4you/2026-11-01/a.ndjson.gz")?.toString(Charsets.UTF_8) shouldBe "abc"
        files.read("boat4you/2026-11-01/missing.ndjson.gz") shouldBe null
        files.read("../secret.txt") shouldBe null
        files.read("/etc/hostname") shouldBe null
    }

    @Test
    fun `a file over the cap is not read`() {
        Files.write(root.resolve("big.bin"), ByteArray(11))
        files.read("big.bin", max = 10) shouldBe null
    }

    @Test
    fun `a stalled share holds at most 4 reads, the next is refused at once`() {
        Files.writeString(root.resolve("x"), "x")
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(RecFiles.MAX_READERS)
        files.readFile = { path ->
            waiting.countDown()
            release.await()
            Files.readAllBytes(path)
        }
        val pool = Executors.newFixedThreadPool(RecFiles.MAX_READERS + 1)
        try {
            val stuck = (1..RecFiles.MAX_READERS).map { pool.submit<ByteArray?> { files.read("x") } }
            waiting.await(5, TimeUnit.SECONDS) shouldBe true
            shouldThrow<RecFiles.BusyException> { files.read("x") }
            release.countDown()
            stuck.map { it.get(5, TimeUnit.SECONDS)?.toString(Charsets.UTF_8) } shouldBe List(RecFiles.MAX_READERS) { "x" }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
