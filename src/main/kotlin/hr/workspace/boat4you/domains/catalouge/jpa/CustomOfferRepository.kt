package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface CustomOfferRepository : JpaRepository<CustomOffer, Long> {
    fun findByShortUrl(shortUrl: String): CustomOffer?

    /**
     * GDPR data export — pulls custom-offer history tied to the user.
     * Used by `DataExportService.exportForUser`.
     */
    fun findAllByUserId(userId: Long): List<CustomOffer>
}
