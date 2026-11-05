package hr.workspace.boat4you.domains.contactcenter.rec

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Reading recordings and archived assets on cusma2 (P6 R2): through the hard NFS share of cusma3, so at most a few reads wait
 * at once and the next waits at most 2 s for a slot before it is refused; nothing outside the recordings folder is ever read.
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

    /** Fills every slot of [read] with a read that hangs until [release], then runs [block]. */
    private fun stalled(
        slots: Int,
        read: () -> ByteArray?,
        block: (release: CountDownLatch, stuck: List<Future<ByteArray?>>, pool: ExecutorService) -> Unit,
    ) {
        Files.writeString(root.resolve("x"), "x")
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(slots)
        files.readFile = { path ->
            if (waiting.count > 0) {
                waiting.countDown()
                release.await()
            }
            Files.readAllBytes(path)
        }
        val pool = Executors.newFixedThreadPool(slots + 1)
        try {
            val stuck = (1..slots).map { pool.submit<ByteArray?> { read() } }
            waiting.await(5, TimeUnit.SECONDS) shouldBe true
            block(release, stuck, pool)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun refusedAfter(read: () -> ByteArray?): Long {
        val start = System.nanoTime()
        shouldThrow<RecFiles.BusyException> { read() }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test
    fun `a stalled share holds at most 4 reads, the next waits at most 2 s and is then refused`() {
        RecFiles.MAX_READERS shouldBe 4
        stalled(RecFiles.MAX_READERS, { files.read("x") }) { release, stuck, _ ->
            refusedAfter { files.read("x") } shouldBeInRange 1_500L..3_000L
            release.countDown()
            stuck.map { it.get(5, TimeUnit.SECONDS)?.toString(Charsets.UTF_8) } shouldBe List(RecFiles.MAX_READERS) { "x" }
        }
    }

    @Test
    fun `8 asset reads run at once, the 9th waits at most 2 s and is then refused`() {
        RecFiles.MAX_ASSET_READERS shouldBe 8
        stalled(RecFiles.MAX_ASSET_READERS, { files.readAsset("x") }) { release, stuck, _ ->
            refusedAfter { files.readAsset("x") } shouldBeInRange 1_500L..3_000L
            release.countDown()
            stuck.map { it.get(5, TimeUnit.SECONDS)?.toString(Charsets.UTF_8) } shouldBe List(RecFiles.MAX_ASSET_READERS) { "x" }
        }
    }

    @Test
    fun `a replay's burst wider than the slots is served - the next asset read takes a slot freed while it waits`() {
        stalled(RecFiles.MAX_ASSET_READERS, { files.readAsset("x") }) { release, _, pool ->
            val next = pool.submit<ByteArray?> { files.readAsset("x") }
            Thread.sleep(300)
            next.isDone shouldBe false
            release.countDown()
            next.get(5, TimeUnit.SECONDS)?.toString(Charsets.UTF_8) shouldBe "x"
        }
    }

    @Test
    fun `public asset reads have their own slots - a stalled raw read never makes an asset read busy`() {
        Files.writeString(root.resolve("x"), "x")
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(RecFiles.MAX_READERS)
        files.readFile = { path ->
            if (path.fileName.toString() == "x") {
                waiting.countDown()
                release.await()
            }
            Files.readAllBytes(path)
        }
        Files.writeString(root.resolve("a.css"), "a")
        val pool = Executors.newFixedThreadPool(RecFiles.MAX_READERS)
        try {
            repeat(RecFiles.MAX_READERS) { pool.submit<ByteArray?> { files.read("x") } }
            waiting.await(5, TimeUnit.SECONDS) shouldBe true
            files.readAsset("a.css")?.toString(Charsets.UTF_8) shouldBe "a"
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
