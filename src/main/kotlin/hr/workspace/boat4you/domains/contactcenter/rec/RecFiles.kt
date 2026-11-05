package hr.workspace.boat4you.domains.contactcenter.rec

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Reading recordings and archived assets on cusma2 (P6 R2). [root] is the NFS share of cusma3's /mnt/data/rec, a hard mount:
 * while cusma3 is away every file call waits, so at most [MAX_READERS] reads run at once; the next waits at most
 * [SLOT_WAIT_MS] for a slot and is then refused with [BusyException], so a stalled share never queues Tomcat threads without
 * end. The public archived assets have their own [MAX_ASSET_READERS] slots, enough for the 4 stylesheets and 6-7 fonts a
 * replay asks for at once (5.11.2026: with 2 slots and no wait 22 % of them were refused). Only paths inside [root] are read.
 */
@Component
class RecFiles(
    @Value("\${cc.rec.root-dir:/mnt/shared/rec}") root: String,
) {
    val root: Path = Path.of(root).toAbsolutePath().normalize()

    /** Reads one file; replaced in tests to stall the share. */
    internal var readFile: (Path) -> ByteArray = Files::readAllBytes

    private val readers = Semaphore(MAX_READERS)

    private val assetReaders = Semaphore(MAX_ASSET_READERS)

    /** The bytes of [rel] under [root]; null when it is missing, outside [root] or larger than [max] bytes. */
    fun read(
        rel: String,
        max: Long = MAX_BYTES,
    ): ByteArray? = read(rel, max, readers)

    private fun read(
        rel: String,
        max: Long,
        slots: Semaphore,
    ): ByteArray? {
        val file = root.resolve(rel.removePrefix("/")).normalize()
        if (!file.startsWith(root)) return null
        val acquired =
            try {
                slots.tryAcquire(SLOT_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!acquired) throw BusyException()
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > max) return null
            return readFile(file)
        } finally {
            slots.release()
        }
    }

    /** As [read], for the public archived assets: their own [MAX_ASSET_READERS] slots, so public requests never take the admin's. */
    fun readAsset(
        rel: String,
        max: Long = MAX_BYTES,
    ): ByteArray? = read(rel, max, assetReaders)

    /** All slots stayed taken for [SLOT_WAIT_MS], most likely by a stalled share. */
    class BusyException : IOException("rec files busy")

    companion object {
        const val MAX_READERS = 4
        const val MAX_ASSET_READERS = 8
        const val SLOT_WAIT_MS = 2_000L
        const val RETRY_AFTER_SECONDS = 2
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
