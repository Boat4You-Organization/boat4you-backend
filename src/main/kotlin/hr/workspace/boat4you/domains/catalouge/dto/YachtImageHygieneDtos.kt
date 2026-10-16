package hr.workspace.boat4you.domains.catalouge.dto

/** PATCH /admin/yacht-images/{id}: a field left out (null) stays as it is. */
data class YachtImageHygieneRequest(
    /** true / false = yacht_image.layout_override (the file-name rule no longer decides for this image). */
    val layout: Boolean? = null,
    /** true = hidden (reason ADMIN: never public, never the main image); false = shown again. */
    val hidden: Boolean? = null,
)

/** One partner image as the admin sees it (V9_91 flags). */
data class YachtImageAdminDto(
    val id: Long,
    val yachtId: Long,
    val position: Short?,
    val mainImage: Boolean?,
    /** The layout drawing as the public API reports it: layoutOverride, else the file name. */
    val layout: Boolean,
    val layoutOverride: Boolean?,
    val hidden: Boolean,
    val hiddenReason: String?,
    /** The boat's card photo (yacht.main_image_id) after the change. */
    val yachtMainImageId: Long?,
)

/** One stored file that is the partner's placeholder (POST /admin/yachts/images/detect-blocked). */
data class BlockedImageDto(
    val imageId: Long,
    val yachtId: Long,
    /** It was the boat's card photo (yacht.main_image_id) or carried the main flag. */
    val main: Boolean,
)

data class BlockedImageScanDto(
    val dryRun: Boolean,
    /** Stored, visible images looked at (their file size). */
    val scanned: Int,
    /** Of those, files of a known placeholder's size, hashed. */
    val hashed: Int,
    /** Files missing on disk. */
    val missingFiles: Int,
    /** The placeholders found: hidden (or, on a dry run, to be hidden). */
    val blocked: List<BlockedImageDto>,
    /** Boats whose card photo moved (apply only): from the placeholder to their next visible image, or none. */
    val mainImagesFixed: List<MainImageFixDto>,
)

/** One image the visual detector measured (POST /admin/yachts/images/detect-layout). */
data class LayoutCandidateDto(
    val imageId: Long,
    val yachtId: Long,
    /** It is the boat's card photo (yacht.main_image_id) or carries the main flag. */
    val main: Boolean,
    val score: Double,
    val whiteFraction: Double,
    val backgroundFraction: Double,
    val contentBackgroundFraction: Double,
    val meanSaturation: Double,
    val edgeDensity: Double,
    val darkComponents: Int,
    val width: Int,
    val height: Int,
)

data class LayoutScanDto(
    val dryRun: Boolean,
    val onlyMain: Boolean,
    /** Images decoded and measured in this call. */
    val scanned: Int,
    /** Files missing or not decodable. */
    val unreadable: Int,
    /** Pass as afterId for the next page; null = the scan reached the end (always null for a call with imageIds). */
    val nextAfterId: Long?,
    /** The images the detector calls a layout drawing: layout_override = true set (or, on a dry run, to be set). */
    val candidates: List<LayoutCandidateDto>,
    /** Boats whose card photo moved off a candidate (apply only). */
    val mainImagesFixed: List<MainImageFixDto>,
    /** imageIds asked for that are not candidates now (gone, hidden, already decided, no longer main, or no longer flagged): untouched. */
    val skippedImageIds: List<Long> = emptyList(),
)
