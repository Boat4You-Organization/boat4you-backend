package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.common.services.ImageService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

enum class ImageSyncOutcome { SUCCESS, FAILED, SKIPPED }

@Service
class YachtImageIntegrationServiceAsync(
    private val imageService: ImageService,
    private val fileSystemService: FileSystemService,
    private val transactionTemplate: TransactionTemplate,
    private val yachtImageSyncService: YachtImageSyncService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Negative cache for partner image URLs that failed to download or decode.
     * Failed images never get `synced=true`, so every ImageDownloadJob run (12x/day)
     * re-fetched the same ~500 dead partner URLs and ERROR-logged each one —
     * ~10k log lines/day for URLs that are dead on the partner side. Same rule as
     * [PartnerAccessGuard]: don't re-ask for data we won't get; re-probe after
     * [RETRY_DEAD_URL_AFTER] so a fixed image heals on its own. In-memory on
     * purpose — resets on deploy, worst case is one extra probe cycle.
     */
    private val deadUrls = ConcurrentHashMap<String, Instant>()

    @Async("imageDownloadTaskExecutor")
    fun syncOffersForAgencyYachts(
        yachtImageId: Long,
        yachtId: Long,
        externalImageUrl: String,
    ): CompletableFuture<ImageSyncOutcome> {
        if (shouldSkipDeadUrl(externalImageUrl)) {
            return CompletableFuture.completedFuture(ImageSyncOutcome.SKIPPED)
        }
        try {
            val image = imageService.downloadAsWebp(externalImageUrl)
            // Result.failure = download failed; success(null) = bytes were not a decodable image
            val imageBytes = image.getOrNull()
            if (imageBytes == null) {
                deadUrls[externalImageUrl] = Instant.now()
                val reason = image.exceptionOrNull()?.message ?: "downloaded bytes are not a decodable image"
                log.warn("Failed to download image for yacht $yachtId from URL: $externalImageUrl because of: $reason")
                return CompletableFuture.completedFuture(ImageSyncOutcome.FAILED)
            }
            val result = fileSystemService.saveImage(imageBytes, "y-$yachtId")
            if (result.isFailure) {
                val cause = result.exceptionOrNull()
                if (cause is IllegalArgumentException) {
                    // validation reject (size cap, undecodable) — deterministic per URL,
                    // retrying every run can't succeed; back off like a dead URL
                    deadUrls[externalImageUrl] = Instant.now()
                    log.warn("Failed to save image for yacht $yachtId from URL: $externalImageUrl because of: ${cause.message}")
                } else {
                    // local disk/NFS problem, not a dead partner URL — stay loud, retry next run
                    log.error("Failed to save image for yacht $yachtId: ${cause?.message}")
                }
                return CompletableFuture.completedFuture(ImageSyncOutcome.FAILED)
            }

            transactionTemplate.execute<Unit> {
                yachtImageSyncService.updateYachtImageWithUrl(yachtImageId, result.getOrNull()!!)
            }
            deadUrls.remove(externalImageUrl)
        } catch (e: Exception) {
            log.error("Failed syncing image $externalImageUrl", e)
            return CompletableFuture.completedFuture(ImageSyncOutcome.FAILED)
        }
        return CompletableFuture.completedFuture(ImageSyncOutcome.SUCCESS)
    }

    private fun shouldSkipDeadUrl(url: String): Boolean {
        val failedAt = deadUrls[url] ?: return false
        if (Duration.between(failedAt, Instant.now()) >= RETRY_DEAD_URL_AFTER) {
            deadUrls.remove(url)
            return false
        }
        return true
    }

    companion object {
        private val RETRY_DEAD_URL_AFTER: Duration = Duration.ofDays(7)
    }
}
