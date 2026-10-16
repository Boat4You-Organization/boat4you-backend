package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.config.RealPriceSwitchesFixture
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import org.mockito.Mockito.mock

/** A [YachtGalleryService] with GALLERY_EXTENDED at its default (OFF): the boat page gallery is the boat's own images. */
object YachtGalleryFixture {
    fun off(): YachtGalleryService = YachtGalleryService(RealPriceSwitchesFixture.switches(), mock(ModelDonorLookup::class.java), mock(CatalogueImageRepository::class.java))
}
