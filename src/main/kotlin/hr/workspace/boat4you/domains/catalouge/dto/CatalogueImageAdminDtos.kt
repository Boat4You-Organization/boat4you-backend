package hr.workspace.boat4you.domains.catalouge.dto

import java.time.Instant

/** One catalogue photo in the admin API (SYSTEM_ADMIN only - licence, source and uploader are never public). */
data class CatalogueImageAdminDto(
    val id: Long,
    val kind: String,
    val locationId: Long?,
    /** Stored path under the upload dir; the photo itself is served as /public/image/{id}. */
    val url: String,
    val caption: String?,
    val credit: String?,
    val sourceUrl: String?,
    val licence: String?,
    val position: Short,
    val active: Boolean,
    val created: Instant?,
    val createdBy: String?,
)

/** POST /admin/locations/{id}/images/from-url: the server downloads each url (credit required, sourceUrl defaults to url). */
data class CatalogueImageFromUrlRequest(
    val urls: List<CatalogueImageFromUrlItem>? = null,
)

data class CatalogueImageFromUrlItem(
    val url: String? = null,
    val caption: String? = null,
    val credit: String? = null,
    val sourceUrl: String? = null,
    val licence: String? = null,
)

/** PUT /admin/catalogue-images/{id}: a field left out (null) stays as it is; a blank caption clears it. */
data class CatalogueImageUpdateRequest(
    val position: Short? = null,
    val active: Boolean? = null,
    val caption: String? = null,
    val credit: String? = null,
)

/** POST /admin/yachts/main-image/fix-layout: one boat whose card photo was its layout drawing, a hidden or a missing image. */
data class MainImageFixDto(
    val yachtId: Long,
    /** null: the boat had no card photo (an admin un-hid its only image). */
    val fromImageId: Long?,
    /** null: the boat has no visible stored image left (only the partner's placeholder) - the sites show their default. */
    val toImageId: Long?,
)

data class MainImageFixResultDto(
    val dryRun: Boolean,
    /** Active boats whose main_image_id is a layout drawing. */
    val layoutMainCount: Int,
    /** The ones with a stored photo (and every hidden / missing main): set (or, on a dry run, to be set) to it. */
    val fixed: List<MainImageFixDto>,
    /** Layout mains without any stored photo: left as they are. */
    val withoutPhotoYachtIds: List<Long>,
    /** Active boats whose main_image_id is a hidden image (V9_91, e.g. the partner's placeholder). */
    val hiddenMainCount: Int = 0,
    /** Active boats whose main_image_id names no image of the boat. */
    val missingMainCount: Int = 0,
)
