package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import kotlin.jvm.optionals.getOrNull

@Service
class YachtImageSyncService(
    private val yachtImageRepository: YachtImageRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateYachtImageWithUrl(
        yachtImageId: Long,
        url: String,
    ) {
        val yachtImage = yachtImageRepository.findById(yachtImageId).getOrNull()
        if (yachtImage == null) {
            log.warn("Unable to find yachtImage with ID $yachtImageId")
            return
        }
        yachtImage.url = url
        yachtImage.synced = true

        yachtImageRepository.save(yachtImage)
    }
}
