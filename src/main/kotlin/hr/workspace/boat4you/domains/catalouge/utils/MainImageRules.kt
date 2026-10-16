package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage

/**
 * The image the MMK / NauSys sync writes to yacht.main_image_id - the search card's photo (yacht_search_view.main_image)
 * - and, read with the same rule, the one main image of the public detail (YachtImageTranslators.toPublicDtos: the boat
 * page hero, share card, PDF cover). Never the layout drawing ([LayoutImageRules]) while the boat has a photo: on
 * 16.10.2026, 11 offered boats showed their deck plan on the card although they have photos, because the partner's
 * "main" / first image was the plan. Never a hidden image (V9_91, e.g. the partner's "Picture is not shown due to
 * possible legal issues" placeholder NauSys flags as mainPicture - owner incident 16.10.2026, FuFu 4345) at all.
 *
 * In order: the partner's main image when it is a photo; the current main when it is still a photo of the boat (a boat
 * without a partner main keeps its card photo, so main_image_id - and the sitemap lastmod, V9_71 - does not change on
 * every sync); the photo with the lowest position. Only a boat without any photo keeps the old pick (partner main, else
 * the first image), the plan included; a boat whose every image is hidden has none (null: the web shows its default).
 */
object MainImageRules {
    private val byPosition = compareBy<YachtImage, Short?>(nullsLast()) { it.position }.thenBy(nullsLast()) { it.id }

    /** V9_91: never public, never the main image. */
    fun isHidden(image: YachtImage): Boolean = image.hidden

    /** The layout drawing: an admin's / the detector's [YachtImage.layoutOverride] when set, else the file name. */
    fun isLayout(image: YachtImage): Boolean = image.layoutOverride ?: LayoutImageRules.isLayout(image.externalUrl, image.url)

    fun pick(
        images: Collection<YachtImage>,
        currentMainId: Long?,
    ): YachtImage? {
        val visible = images.filterNot(::isHidden)
        val photos = visible.filterNot(::isLayout)
        return photos.firstOrNull { it.mainImage == true }
            ?: photos.firstOrNull { currentMainId != null && it.id == currentMainId }
            ?: photos.minWithOrNull(byPosition)
            ?: visible.firstOrNull { it.mainImage == true }
            ?: visible.firstOrNull()
    }

    /**
     * After a partner sync wrote the partner's flags and [pick] chose [mainId]: exactly that image carries
     * yacht_image.main_image (none when [mainId] is null), so a hidden image or a layout drawing never keeps the
     * partner's flag while the boat has a photo. The next sync re-applies the partner's flags before [pick], so the
     * partner's choice still leads. Returns the images whose flag changed (the caller saves them).
     */
    fun flagMain(
        images: Collection<YachtImage>,
        mainId: Long?,
    ): List<YachtImage> {
        val changed = mutableListOf<YachtImage>()
        images.forEach { image ->
            val main = mainId != null && image.id == mainId
            if (image.mainImage != main) {
                image.mainImage = main
                changed += image
            }
        }
        return changed
    }
}
