package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType

/** PUT /admin/yachts/{id}/images/order: the boat's images in the new order (the ones left out follow in today's order). */
data class YachtImageOrderRequest(
    val imageIds: List<Long>? = null,
)

/** Our media on a boat as the admin sees it (V9_92): the answer of every /admin/yachts/{id} media call. */
data class YachtMediaDto(
    val yachtId: Long,
    /** yacht.main_image_id: the card photo, the page hero. */
    val mainImageId: Long?,
    val videoUrl: String?,
    /** The public URL of the crew profile PDF, as /public/yachts/{slug} gives it; null = none. */
    val crewProfileUrl: String?,
    /** Every image of the boat in position order (the public order), hidden ones included. */
    val images: List<YachtImageAdminDto>,
)

/** One boat of GET /admin/yachts/lookup (name or id). */
data class YachtLookupDto(
    val id: Long,
    val name: String,
    val manufacturerName: String?,
    val modelName: String?,
    val slug: String,
    val entryType: EntryType?,
    val vesselType: VesselType?,
    val mainImageId: Long?,
    /** Visible stored images (what the boat page can show). */
    val imageCount: Int,
    /** yacht.video_url or a custom boat's video. */
    val hasVideo: Boolean,
    val hasCrewProfile: Boolean,
)
