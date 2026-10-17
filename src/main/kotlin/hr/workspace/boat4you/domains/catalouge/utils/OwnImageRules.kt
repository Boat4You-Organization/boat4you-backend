package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.enums.YachtImageSource
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage

/**
 * Our own photos on any boat (V9_92, owner request 17.10.2026, gulet Allure 11769): rows with source OWN come first by
 * position, the partner's pictures follow in the partner's order. The MMK / NauSys syncs match, move, flag and delete
 * only the partner's rows ([partnerImages]) and place them from [partnerOffset] on; an OWN row is never deleted, moved or
 * re-flagged by a sync (its main flag follows MainImageRules, which keeps an OWN main).
 */
object OwnImageRules {
    fun isOwn(image: YachtImage): Boolean = image.source == YachtImageSource.OWN

    /** The rows a partner sync may match, move, flag and delete. */
    fun partnerImages(images: Collection<YachtImage>): List<YachtImage> = images.filterNot(::isOwn)

    /** First position of the partner's pictures: right after the last OWN photo; 0 without one (today's positions). */
    fun partnerOffset(images: Collection<YachtImage>): Int = images.filter(::isOwn).maxOfOrNull { it.position?.toInt() ?: -1 }?.let { it + 1 } ?: 0
}
