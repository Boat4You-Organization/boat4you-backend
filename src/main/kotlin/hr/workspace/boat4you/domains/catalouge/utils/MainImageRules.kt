package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage

/**
 * The image the MMK / NauSys sync writes to yacht.main_image_id - the search card's photo (yacht_search_view.main_image).
 * Never the layout drawing ([LayoutImageRules]) while the boat has a photo: on 16.10.2026, 11 offered boats showed their
 * deck plan on the card although they have photos, because the partner's "main" / first image was the plan.
 *
 * In order: the partner's main image when it is a photo; the current main when it is still a photo of the boat (a boat
 * without a partner main keeps its card photo, so main_image_id - and the sitemap lastmod, V9_71 - does not change on
 * every sync); the photo with the lowest position. Only a boat without any photo keeps the old pick (partner main, else
 * the first image), the plan included.
 */
object MainImageRules {
    private val byPosition = compareBy<YachtImage, Short?>(nullsLast()) { it.position }.thenBy(nullsLast()) { it.id }

    fun isLayout(image: YachtImage): Boolean = LayoutImageRules.isLayout(image.externalUrl, image.url)

    fun pick(
        images: Collection<YachtImage>,
        currentMainId: Long?,
    ): YachtImage? {
        val photos = images.filterNot(::isLayout)
        return photos.firstOrNull { it.mainImage == true }
            ?: photos.firstOrNull { currentMainId != null && it.id == currentMainId }
            ?: photos.minWithOrNull(byPosition)
            ?: images.firstOrNull { it.mainImage == true }
            ?: images.firstOrNull()
    }
}
