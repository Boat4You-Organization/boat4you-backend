package hr.workspace.boat4you.domains.contactcenter.rec

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The recording spool on cusma2 (P6 R1.3): every payload as received, one file each, under `<root>/spool/<yyyyMMddHHmm>/`
 * (UTC minute), named `<epochMs>-<site>-<uuid>.gz|json`. [root] is the NFS share of cusma3's /mnt/data (`/mnt/shared/rec`);
 * the worker on cusma3 reads the same folder. A file appears complete or not at all: written as `.<name>.tmp`, then renamed.
 */
@Component
class RecSpool(
    @Value("\${cc.rec.root-dir:/mnt/shared/rec}") root: String,
) {
    val root: Path = Path.of(root)

    internal var clock: Clock = Clock.systemUTC()

    /** Monotonic clock in nanoseconds; replaced in tests. */
    internal var ticker: () -> Long = System::nanoTime

    @Volatile private var quota: Pair<Boolean, Long>? = null

    /** The worker's quota flag: true while `<root>/QUOTA_FULL` exists; looked at once per [QUOTA_CHECK_NANOS]. */
    fun quotaFull(): Boolean {
        val now = ticker()
        quota?.let { (full, at) -> if (now - at < QUOTA_CHECK_NANOS) return full }
        return Files.exists(root.resolve(QUOTA_FLAG)).also { quota = it to now }
    }

    fun write(
        siteId: String,
        body: ByteArray,
        gzip: Boolean,
    ): Path {
        val now = clock.instant()
        val dir = root.resolve(SPOOL).resolve(MINUTE.format(now))
        Files.createDirectories(dir)
        val name = "${now.toEpochMilli()}-$siteId-${UUID.randomUUID()}.${if (gzip) "gz" else "json"}"
        val tmp = dir.resolve(".$name.tmp")
        Files.write(tmp, body)
        return Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val QUOTA_FLAG = "QUOTA_FULL"
        const val SPOOL = "spool"
        val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)
        private const val QUOTA_CHECK_NANOS = 30_000_000_000L
    }
}
