package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.YachtImageDto
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.utils.MainImageRules

fun YachtImage.toDto(): YachtImageDto =
    YachtImageDto(
        id = id,
        position = position,
        mainImage = mainImage,
        layout = MainImageRules.isLayout(this),
    )

/**
 * The images the public sees (boat page, reservation detail): hidden ones (V9_91) left out, in position order, and
 * exactly one main image when any is left - [MainImageRules.pick] with the boat's card photo ([currentMainId] =
 * yacht.main_image_id), so the page hero, the share card and the PDF cover show the photo the search card shows, never
 * a hidden placeholder and never the layout drawing while the boat has a photo.
 */
fun Collection<YachtImage>.toPublicDtos(currentMainId: Long?): List<YachtImageDto> {
    val visible = filterNot(MainImageRules::isHidden)
    val main = MainImageRules.pick(visible, currentMainId)
    return visible
        // the main true, a partner main that is not ours false, every other flag as stored (null stays null: same JSON as before)
        .map { it.toDto().copy(mainImage = if (it === main) true else it.mainImage?.and(false)) }
        .sortedBy { it.position }
}
