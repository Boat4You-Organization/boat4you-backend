package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface CustomOfferRepository : JpaRepository<CustomOffer, Long> {
    fun findByShortUrl(shortUrl: String): CustomOffer?
}
