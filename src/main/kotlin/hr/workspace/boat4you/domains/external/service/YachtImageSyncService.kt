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
     * and loses the main flag, and when it is the boat's card photo (yacht.main_image_id) the card gets the stored photo
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
        if (hiddenReason == null && yachtImage.hiddenReason == DEAD_PARTNER_URL) {
            // the partner's link delivers again: shown like any photo
            jdbc.update(HEAL_SQL, yachtImageId, DEAD_PARTNER_URL)
            yachtImage.hidden = false
            yachtImage.hiddenReason = null
        }
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

    /**
     * A partner image whose URL delivers no picture (404, HTML, undecodable bytes - the 78 booking-manager document
     * links of Gulet Allure 11769 that showed as empty cards in the admin, 25.10.2026): hidden with [DEAD_PARTNER_URL]
     * so the admin's media page names the empty card. Public pages never showed it (never synced). Only a row still
     * unsynced and not hidden for another reason is marked; [updateYachtImageWithUrl] shows it again when a later
     * download succeeds (the job retries a dead URL after YachtImageIntegrationServiceAsync.RETRY_DEAD_URL_AFTER).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markDeadPartnerUrl(yachtImageId: Long) {
        jdbc.update(MARK_DEAD_SQL, DEAD_PARTNER_URL, yachtImageId)
    }

    private fun repickCardPhoto(
        hiddenImageId: Long,
        yacht: Yacht?,
    ) {
        if (yacht?.id == null || yacht.mainImageId != hiddenImageId) return
        // stored images only, like YachtMainImageFixService.repickMain: the card serves the file, a photo still to be
        // downloaded would show the sites' default picture
        val main = MainImageRules.pick(yacht.yachtImages.filterNot { it.url.isNullOrEmpty() }, null)?.id
        // the old value re-checked: a sync that moved the main meanwhile wins
        val changed = jdbc.update(REPICK_SQL, arrayOf(main, yacht.id, hiddenImageId), intArrayOf(Types.BIGINT, Types.BIGINT, Types.BIGINT))
        if (changed == 1) log.info("Yacht ${yacht.id}: card photo $hiddenImageId was the partner's placeholder, now ${main ?: "none"}")
    }

    companion object {
        /** yacht_image.hidden_reason of a partner image whose URL delivers no picture. */
        const val DEAD_PARTNER_URL = "DEAD_PARTNER_URL"
        private const val HIDE_SQL = "UPDATE yacht_image SET hidden = true, hidden_reason = ?, main_image = false WHERE id = ?"
        private const val MARK_DEAD_SQL =
            "UPDATE yacht_image SET hidden = true, hidden_reason = ? WHERE id = ? AND synced = false AND hidden = false"
        private const val HEAL_SQL = "UPDATE yacht_image SET hidden = false, hidden_reason = NULL WHERE id = ? AND hidden_reason = ?"
        private const val REPICK_SQL = "UPDATE yacht SET main_image_id = ? WHERE id = ? AND main_image_id = ?"
    }
}
