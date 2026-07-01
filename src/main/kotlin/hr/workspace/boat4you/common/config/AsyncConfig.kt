package hr.workspace.boat4you.common.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Configuration
@EnableAsync
class AsyncConfig(
    @Value("\${application.external.sync.image-sync-batch}")
    private val imageSyncBatch: Int?,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(AsyncConfig::class.java)

    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        // 6 caps cache-warm concurrency: bounded partner-API pressure and bounded
        // cusma4 write load from warm syncs. (The original 15->6 rationale — each
        // warm pinning a Hikari connection for the whole partner call — is gone:
        // ExternalSyncService no longer runs its @Async entry points inside a
        // transaction, and the per-yacht path only holds one during the
        // advisory-locked partner call, bounded by the HTTP read timeout.)
        // Mario 30.6.2026 (booking outage). See F1-064.
        executor.corePoolSize = 3
        executor.maxPoolSize = 6
        executor.queueCapacity = 200
        executor.setThreadNamePrefix("AsyncThread-")
        // F1-064: do NOT fall back to caller-runs here. Every consumer
        // of `taskExecutor` is a best-effort partner cache-warm
        // (`ExternalSyncService.syncYachtOffers` from the public yacht
        // search endpoints, `MmkYachtOfferIntegrationServiceAsync` from
        // admin/scheduler). Each call costs up to ~1 minute of
        // wall-clock (F3-001 partner read timeout), so "execute on the
        // caller's thread" under saturation cascades a partner slowdown
        // into request-thread starvation on every Tomcat worker that hit
        // the search endpoint. Dropping the task is cheap —
        // ServiceCallCacheService dedupes via TTL and the next public
        // miss re-triggers the sync naturally. Tasks submitted here must
        // stay self-contained: nothing may join() a future backed by
        // this pool, because a dropped task's future never completes
        // (that combination previously froze cache-warm threads for the
        // full 5-minute orTimeout while they held a DB connection).
        executor.setRejectedExecutionHandler { _, exec ->
            log.warn(
                "taskExecutor saturated (active={}, pool={}, queue={}); dropping task — partner sync will retry on next cache miss (F1-064)",
                exec.activeCount,
                exec.poolSize,
                exec.queue.size,
            )
        }
        executor.initialize()
        return executor
    }

    @Bean(name = ["imageDownloadTaskExecutor"])
    fun imageDownloadTaskExecutor(): Executor {
        // We want other tasks to wait until there is space in the queue

        val queue = ArrayBlockingQueue<Runnable>(100)
        val executor =
            ThreadPoolExecutor(
                3, // core
                imageSyncBatch ?: 10, // max
                0L, // keep alive time
                TimeUnit.MILLISECONDS,
                queue,
                ThreadFactory { r ->
                    Thread(r, "ImgSyncThread-").apply { isDaemon = true }
                },
            )

        executor.rejectedExecutionHandler =
            RejectedExecutionHandler { r, exec ->
                log.warn("Image sync task rejected; applying backpressure on submitter thread.")
                if (exec.isShutdown) return@RejectedExecutionHandler
                try {
                    if (!exec.queue.offer(r, 30, TimeUnit.SECONDS)) {
                        log.warn("Image sync queue still full after 30s; running task in caller thread.")
                        r.run()
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn("Interrupted while waiting for image sync queue capacity.", e)
                }
            }

        return executor
    }
}
