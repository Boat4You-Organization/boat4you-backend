package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import hr.workspace.boat4you.domains.catalouge.utils.MainImageRules
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Types
import kotlin.jvm.optionals.getOrNull

@Service
class YachtImageSyncService(
    private val yachtImageRepository: YachtImageRepository,
    private val jdbc: JdbcTemplate,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * The downloaded file of an image. With [hiddenReason] (the partner's placeholder, V9_91) the image is also hidden
     * and loses the main flag, and when it is the boat's card photo (yacht.main_image_id) the card gets the photo
     * [MainImageRules.pick] chooses now - without waiting for the next partner sync of the boat.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateYachtImageWithUrl(
        yachtImageId: Long,
        url: String,
        hiddenReason: String? = null,
    ) {
        val yachtImage = yachtImageRepository.findById(yachtImageId).getOrNull()
        if (yachtImage == null) {
            log.warn("Unable to find yachtImage with ID $yachtImageId")
            return
        }
        yachtImage.url = url
        yachtImage.synced = true
        if (hiddenReason != null) {
            yachtImage.mainImage = false
            // hidden is read-only for Hibernate (a partner sync re-saving the row must never clear it): written here
            jdbc.update(HIDE_SQL, hiddenReason, yachtImageId)
            yachtImage.hidden = true
            yachtImage.hiddenReason = hiddenReason
            repickCardPhoto(yachtImageId, yachtImage.yacht)
        }

        yachtImageRepository.save(yachtImage)
    }

    private fun repickCardPhoto(
        hiddenImageId: Long,
        yacht: Yacht?,
    ) {
        if (yacht?.id == null || yacht.mainImageId != hiddenImageId) return
        val main = MainImageRules.pick(yacht.yachtImages, null)?.id
        // the old value re-checked: a sync that moved the main meanwhile wins
        val changed = jdbc.update(REPICK_SQL, arrayOf(main, yacht.id, hiddenImageId), intArrayOf(Types.BIGINT, Types.BIGINT, Types.BIGINT))
        if (changed == 1) log.info("Yacht ${yacht.id}: card photo $hiddenImageId was the partner's placeholder, now ${main ?: "none"}")
    }

    companion object {
        private const val HIDE_SQL = "UPDATE yacht_image SET hidden = true, hidden_reason = ?, main_image = false WHERE id = ?"
        private const val REPICK_SQL = "UPDATE yacht SET main_image_id = ? WHERE id = ? AND main_image_id = ?"
    }
}
