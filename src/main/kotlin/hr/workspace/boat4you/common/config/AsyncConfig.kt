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
        executor.corePoolSize = 5
        executor.maxPoolSize = 15
        executor.queueCapacity = 200
        executor.setThreadNamePrefix("AsyncThread-")
        // F1-064: do NOT fall back to caller-runs here. Every consumer
        // of `taskExecutor` is a best-effort partner cache-warm
        // (`ExternalSyncService.syncYachtOffers`, `NauSys`/`MMK`
        // YachtOfferIntegrationServiceAsync — all triggered from the
        // public yacht search endpoints in YachtController). Each call
        // costs up to ~1 minute of wall-clock (F3-001 partner read
        // timeout), so "execute on the caller's thread" under
        // saturation cascades a partner slowdown into request-thread
        // starvation on every Tomcat worker that hit the search
        // endpoint. Dropping the task is cheap — ServiceCallCacheService
        // dedupes via TTL and the next public miss re-triggers the
        // sync naturally.
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
