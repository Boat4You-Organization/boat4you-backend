package hr.workspace.boat4you.domains.external.job

import hr.workspace.boat4you.domains.external.service.YachtImageIntegrationService
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync & image-sync")
@Component
class ImageDownloadJob(
    private val yachtImageIntegrationService: YachtImageIntegrationService,
) {
    /**
     * Download missing images
     */
    @Scheduled(cron = "0 50 */2 * * ?")
    fun runImageDownload() {
        yachtImageIntegrationService.downloadImages()
    }
}
