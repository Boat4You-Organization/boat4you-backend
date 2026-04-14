package hr.workspace.boat4you.domains.external.service

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
    @Value("\${application.external.sync.image-sync-count}")
    private val imageSyncCount: Int?,
    @Value("\${application.external.sync.image-sync-batch}")
    private val imageSyncBatch: Int?,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun downloadImages() {
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

        images.chunked(batchSize).forEach { batch ->
            val futures =
                batch.map { yachtImage ->
                    yachtImageIntegrationServiceAsync.syncOffersForAgencyYachts(
                        yachtImage.id!!,
                        yachtImage.yacht!!.id!!,
                        yachtImage.externalUrl!!,
                    )
                }
            CompletableFuture.allOf(*futures.toTypedArray()).join()
        }
    }
}
