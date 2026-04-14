package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.common.services.ImageService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CompletableFuture

@Service
class YachtImageIntegrationServiceAsync(
    private val imageService: ImageService,
    private val fileSystemService: FileSystemService,
    private val transactionTemplate: TransactionTemplate,
    private val yachtImageSyncService: YachtImageSyncService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Async("imageDownloadTaskExecutor")
    fun syncOffersForAgencyYachts(
        yachtImageId: Long,
        yachtId: Long,
        externalImageUrl: String,
    ): CompletableFuture<Unit> {
        try {
            val image = imageService.downloadAsWebp(externalImageUrl)
            if (image.isFailure) {
                val errorMessage = image.exceptionOrNull()?.message
                log.error("Failed to download image from URL: $externalImageUrl because of: $errorMessage")
                return CompletableFuture.completedFuture(Unit)
            }
            val result =
                try {
                    fileSystemService.saveImage(image.getOrNull()!!, "y-$yachtId")
                } catch (e: NullPointerException) {
                    log.error("Failed to download image from URL: $externalImageUrl")
                    Result.failure(e)
                } catch (e: Exception) {
                    log.error("Failed to download image from URL: $externalImageUrl", e)
                    Result.failure(e)
                }
            if (result.isFailure) {
                log.error("Failed to save image for yacht $yachtId: ${result.exceptionOrNull()?.message}")
                return CompletableFuture.completedFuture(Unit)
            }

            transactionTemplate.execute<Unit> {
                yachtImageSyncService.updateYachtImageWithUrl(yachtImageId, result.getOrNull()!!)
            }
        } catch (e: Exception) {
            log.error("Failed syncing image $externalImageUrl", e)
        }
        return CompletableFuture.completedFuture(Unit)
    }
}
