package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OfferMutationService(
    private val offerRepository: OfferRepository,
) {
    @Transactional
    @Caching(
        evict = [
            CacheEvict(value = ["offersByYachtAndStatusCache"], allEntries = true),
        ],
    )
    fun updateOfferStatus(
        offerId: Long,
        newStatus: OfferStatus,
    ) {
        val offer = offerRepository.findById(offerId).get()
        offer.status = newStatus
        offerRepository.save(offer)
    }
}
