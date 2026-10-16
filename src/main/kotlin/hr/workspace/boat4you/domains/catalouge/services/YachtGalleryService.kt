package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageDto
import hr.workspace.boat4you.domains.catalouge.jpa.CatalogueImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import org.springframework.stereotype.Service
import java.util.Locale

/**
 * The boat page gallery with model and marina photos (Mario 16.10.2026, photos DESIGN.md section Backend), behind the
 * switch GALLERY_EXTENDED. OFF (the default) returns the boat's own images untouched: today's answer, byte for byte.
 * Read time only - nothing is written to the partner-owned yacht_image rows. Used by the detail of
 * `/public/yachts/{slug}` only (YachtMapper.toDetailsDto); the search cards, reservations and custom-boat answers keep
 * their own image lists.
 *
 * ON:
 * - every image carries `kind`: the boat's own [YachtImageDto.KIND_PARTNER], or [YachtImageDto.KIND_LAYOUT] for a
 *   layout drawing;
 * - a boat without one real photo (none, or only layout drawings) of a model that is not generic gets up to
 *   [MAX_MODEL_SAMPLES] photos of a donor boat of the same model ([ModelDonorLookup]): their real yacht_image ids,
 *   [YachtImageDto.KIND_MODEL_SAMPLE], positions from [MODEL_SAMPLE_POSITION], the first one the main image (the boat's
 *   own layout drawings then drop the flag, so the answer has one main image);
 * - up to [MAX_MARINA_PHOTOS] photos of the departure marina - the page's location (the period location of a dated
 *   request, else the home base) and the rows paired with it in location_same_place - as [YachtImageDto.KIND_MARINA],
 *   last (positions from [MARINA_POSITION]), never the main image, with caption (the photo's own, else the marina's
 *   display name), credit and source url.
 */
@Service
class YachtGalleryService(
    private val switches: RealPriceSwitches,
    private val modelDonors: ModelDonorLookup,
    private val catalogueImages: CatalogueImageRepository,
) {
    fun extend(
        yacht: Yacht,
        own: List<YachtImageDto>,
        location: LocationDto?,
    ): List<YachtImageDto> {
        if (!switches.galleryExtended.isOn) return own
        val tagged = own.map { it.copy(kind = if (it.layout) YachtImageDto.KIND_LAYOUT else YachtImageDto.KIND_PARTNER) }
        val samples = if (tagged.any { !it.layout }) emptyList() else modelSamples(yacht)
        val base = if (samples.isEmpty()) tagged else tagged.map { it.copy(mainImage = false) }
        return base + samples + marinaPhotos(location)
    }

    private fun modelSamples(yacht: Yacht): List<YachtImageDto> {
        val donorId = donorOf(yacht) ?: return emptyList()
        return modelDonors.donorPhotoIds(donorId).take(MAX_MODEL_SAMPLES).mapIndexed { i, imageId ->
            YachtImageDto(
                id = imageId,
                position = (MODEL_SAMPLE_POSITION + i).toShort(),
                mainImage = i == 0,
                kind = YachtImageDto.KIND_MODEL_SAMPLE,
            )
        }
    }

    /** The donor boat of [yacht]'s model, or null: no model, a generic one, or no other boat of it qualifies. */
    private fun donorOf(yacht: Yacht): Long? {
        val modelId = yacht.model?.takeUnless { isGenericModel(it) }?.id ?: return null
        return modelDonors.donorFor(modelId, yacht.location?.countryCode).yachtId?.takeIf { it != yacht.id }
    }

    private fun marinaPhotos(location: LocationDto?): List<YachtImageDto> {
        // "l-{id}" for a partner base; a custom boat's location is its country ("C-{id}"), which has no marina photos.
        val locationId = location?.id?.takeIf { it.startsWith(LOCATION_ID_PREFIX) }?.removePrefix(LOCATION_ID_PREFIX)?.toLongOrNull()
        if (locationId == null) return emptyList()
        val ids = (listOf(locationId) + catalogueImages.findSamePlacePartnerIds(locationId)).distinct()
        return catalogueImages.findActiveByLocationIds(ids).take(MAX_MARINA_PHOTOS).mapIndexed { i, photo ->
            YachtImageDto(
                id = photo.id,
                position = (MARINA_POSITION + i).toShort(),
                mainImage = false,
                kind = YachtImageDto.KIND_MARINA,
                caption = photo.caption?.takeIf { it.isNotBlank() } ?: location.name,
                credit = photo.credit,
                sourceUrl = photo.sourceUrl,
            )
        }
    }

    companion object {
        const val MAX_MODEL_SAMPLES = 8
        const val MAX_MARINA_PHOTOS = 3
        const val MODEL_SAMPLE_POSITION = 1000
        const val MARINA_POSITION = 2000
        private const val LOCATION_ID_PREFIX = "l-"

        /** Model rows that name a kind of boat, not a model: their boats share nothing but the word. */
        val GENERIC_MODEL_NAMES: Set<String> =
            setOf("motor sailer", "motorboat", "motoryacht", "sailing yacht", "custom", "custom made")

        /** No builder, or a [GENERIC_MODEL_NAMES] name (trimmed, any case). */
        fun isGenericModel(model: Model): Boolean = model.manufacturer == null || model.name?.trim()?.lowercase(Locale.ROOT) in GENERIC_MODEL_NAMES
    }
}
