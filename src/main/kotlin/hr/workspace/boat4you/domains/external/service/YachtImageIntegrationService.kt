package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import kotlin.collections.chunked
import kotlin.collections.map

@Service
class YachtImageIntegrationService(
    private val yachtImageIntegrationServiceAsync: YachtImageIntegrationServiceAsync,
    private val yachtImageRepository: YachtImageRepository,
    private val fileSystemService: FileSystemService,
    @Value("\${application.external.sync.image-sync-count}")
    private val imageSyncCount: Int?,
    @Value("\${application.external.sync.image-sync-batch}")
    private val imageSyncBatch: Int?,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun downloadImages() {
        purgeImagesOfRemovedYachts()

        val count = yachtImageRepository.countYachtImageBySyncedFalse()
        if (count == 0L) {
            return
        }
        log.info("Unsynced images count $count")

        val pageSize = imageSyncCount ?: 2000
        val images = yachtImageRepository.findBySyncedFalseOrderByIdDesc(PageRequest.of(0, pageSize))
        if (images.isEmpty()) {
            return
        }

        val batchSize = imageSyncBatch ?: 10

        var failed = 0
        var skipped = 0
        val failedYachts = mutableSetOf<Long>()
        images.chunked(batchSize).forEach { batch ->
            val futures =
                batch.map { yachtImage ->
                    yachtImage to
                        yachtImageIntegrationServiceAsync.syncOffersForAgencyYachts(
                            yachtImage.id!!,
                            yachtImage.yacht!!.id!!,
                            yachtImage.externalUrl!!,
                        )
                }
            CompletableFuture.allOf(*futures.map { it.second }.toTypedArray()).join()
            futures.forEach { (yachtImage, future) ->
                when (future.join()) {
                    ImageSyncOutcome.FAILED -> {
                        failed++
                        failedYachts.add(yachtImage.yacht!!.id!!)
                    }
                    ImageSyncOutcome.SKIPPED -> skipped++
                    ImageSyncOutcome.SUCCESS -> {}
                }
            }
        }
        if (failed > 0 || skipped > 0) {
            log.warn(
                "Image sync: $failed of ${images.size} images failed for ${failedYachts.size} yachts; " +
                    "$skipped skipped as known-dead (re-probed after 7 days)",
            )
        }
    }

    /**
     * Taken-back (deactivated) yacht -> its images go too, rows and files; a
     * deactivated yacht is never synced again so they would live forever
     * otherwise. Partner-sourced images only: the catalogue sync recreates
     * them if the yacht ever returns. No-op once clean.
     */
    private fun purgeImagesOfRemovedYachts() {
        var purged = 0
        while (true) {
            val batch = yachtImageRepository.findPurgeableImagesOfInactiveYachts(PageRequest.of(0, 500))
            if (batch.isEmpty()) break
            batch.forEach { image -> image.url?.let { fileSystemService.deleteFile(it) } }
            yachtImageRepository.deleteAll(batch)
            purged += batch.size
        }
        if (purged > 0) {
            log.info("Purged $purged images of deactivated yachts")
        }
    }
}
