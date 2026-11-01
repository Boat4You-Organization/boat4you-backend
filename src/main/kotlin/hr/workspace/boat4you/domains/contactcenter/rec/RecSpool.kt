package hr.workspace.boat4you.domains.contactcenter.rec

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The recording spool on cusma2 (P6 R1.3): every payload as received, one file each, under `<root>/spool/<yyyyMMddHHmm>/`
 * (UTC minute), named `<epochMs>-<site>-<uuid>.gz|json`. [root] is the NFS share of cusma3's /mnt/data (`/mnt/shared/rec`);
 * the worker on cusma3 reads the same folder. A file appears complete or not at all: written as `.<name>.tmp`, then renamed.
 *
 * The share is a hard NFS mount: while cusma3 is away, every file call waits. So a request never looks at the share for the
 * quota flag (a daemon thread does, every 30 s) and at most [MAX_WRITERS] requests write at once; the next one is refused at
 * once with [SpoolBusyException], so a stalled share can hold 8 Tomcat threads, never the whole API.
 */
@Component
class RecSpool(
    @Value("\${cc.rec.root-dir:/mnt/shared/rec}") root: String,
) {
    val root: Path = Path.of(root)

    internal var clock: Clock = Clock.systemUTC()

    /** Writes one file; replaced in tests to stall the share. */
    internal var writeFile: (Path, ByteArray) -> Unit = { path, bytes -> Files.write(path, bytes) }

    private val writers = Semaphore(MAX_WRITERS)

    @Volatile private var quotaFlag = false

    private val quotaChecker =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "rec-quota-flag").apply { isDaemon = true } }

    @PostConstruct
    fun start() {
        quotaChecker.scheduleWithFixedDelay({ runCatching { refreshQuota() } }, 0, QUOTA_CHECK_SECONDS, TimeUnit.SECONDS)
    }

    @PreDestroy
    fun stop() {
        quotaChecker.shutdownNow()
    }

    /** Looks at the worker's quota flag (`<root>/QUOTA_FULL`) on the share; only the background thread and tests call this. */
    fun refreshQuota() {
        quotaFlag = Files.exists(root.resolve(QUOTA_FLAG))
    }

    /** The quota flag as last seen by [refreshQuota]; never touches the share. */
    fun quotaFull(): Boolean = quotaFlag

    fun write(
        siteId: String,
        body: ByteArray,
        gzip: Boolean,
    ): Path {
        if (!writers.tryAcquire()) throw SpoolBusyException()
        try {
            val now = clock.instant()
            val dir = root.resolve(SPOOL).resolve(MINUTE.format(now))
            Files.createDirectories(dir)
            val name = "${now.toEpochMilli()}-$siteId-${UUID.randomUUID()}.${if (gzip) "gz" else "json"}"
            val tmp = dir.resolve(".$name.tmp")
            writeFile(tmp, body)
            return Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            writers.release()
        }
    }

    /** All [MAX_WRITERS] slots are taken, most likely by a stalled share. */
    class SpoolBusyException : IOException("rec spool busy")

    companion object {
        const val QUOTA_FLAG = "QUOTA_FULL"
        const val SPOOL = "spool"
        val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)
        const val MAX_WRITERS = 8
        private const val QUOTA_CHECK_SECONDS = 30L
    }
}
