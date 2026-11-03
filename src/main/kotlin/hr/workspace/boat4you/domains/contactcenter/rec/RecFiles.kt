package hr.workspace.boat4you.domains.contactcenter.rec

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Semaphore

/**
 * Reading recordings and archived assets on cusma2 (P6 R2). [root] is the NFS share of cusma3's /mnt/data/rec, a hard mount:
 * while cusma3 is away every file call waits, so at most [MAX_READERS] reads run at once and the next is refused at once with
 * [BusyException] (as the spool's writers, RecSpool). Only paths inside [root] are read.
 */
@Component
class RecFiles(
    @Value("\${cc.rec.root-dir:/mnt/shared/rec}") root: String,
) {
    val root: Path = Path.of(root).toAbsolutePath().normalize()

    /** Reads one file; replaced in tests to stall the share. */
    internal var readFile: (Path) -> ByteArray = Files::readAllBytes

    private val readers = Semaphore(MAX_READERS)

    /** The bytes of [rel] under [root]; null when it is missing, outside [root] or larger than [max] bytes. */
    fun read(
        rel: String,
        max: Long = MAX_BYTES,
    ): ByteArray? {
        val file = root.resolve(rel.removePrefix("/")).normalize()
        if (!file.startsWith(root)) return null
        if (!readers.tryAcquire()) throw BusyException()
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > max) return null
            return readFile(file)
        } finally {
            readers.release()
        }
    }

    /** All [MAX_READERS] slots are taken, most likely by a stalled share. */
    class BusyException : IOException("rec files busy")

    companion object {
        const val MAX_READERS = 4
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
