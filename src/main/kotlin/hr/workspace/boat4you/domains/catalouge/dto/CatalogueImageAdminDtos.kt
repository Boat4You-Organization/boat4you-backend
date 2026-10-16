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

/** POST /admin/yachts/main-image/fix-layout: one boat whose card photo was its layout drawing. */
data class MainImageFixDto(
    val yachtId: Long,
    val fromImageId: Long,
    val toImageId: Long,
)

data class MainImageFixResultDto(
    val dryRun: Boolean,
    /** Active boats whose main_image_id is a layout drawing. */
    val layoutMainCount: Int,
    /** The ones with a stored photo: set (or, on a dry run, to be set) to it. */
    val fixed: List<MainImageFixDto>,
    /** The ones without any stored photo: left as they are. */
    val withoutPhotoYachtIds: List<Long>,
)
