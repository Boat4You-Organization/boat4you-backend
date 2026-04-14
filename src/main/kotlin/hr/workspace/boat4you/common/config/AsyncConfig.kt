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
        executor.setRejectedExecutionHandler { r, exec ->
            log.warn("Task rejected, thread pool is full. Executing in caller's thread.")
            if (!exec.isShutdown) {
                r.run()
            }
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
                // BLOCK until queue has space.
                // For img sync jobs, it should not lead to starvation
                println("Task rejected, thread pool is full")
                exec.queue.put(r)
            }

        return executor
    }
}
