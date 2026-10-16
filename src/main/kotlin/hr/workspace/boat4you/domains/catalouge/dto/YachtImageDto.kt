package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonInclude

data class YachtImageDto(
    val id: Long?,
    val position: Short? = null,
    val mainImage: Boolean? = null,
    /** The boat's layout (deck / floor plan), read from the file name: [hr.workspace.boat4you.domains.catalouge.utils.LayoutImageRules]. */
    val layout: Boolean = false,
    /**
     * Boat page only, with GALLERY_EXTENDED=ON (YachtGalleryService): [KIND_PARTNER] (the boat's own photo), [KIND_LAYOUT]
     * (its layout drawing, = [layout]), [KIND_MODEL_SAMPLE] (a photo of another boat of the same model) or [KIND_MARINA]
     * (the departure marina). Left out of the JSON otherwise - every answer stays byte for byte as before; a client
     * without it reads [layout] as today.
     */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val kind: String? = null,
    /** MARINA only: the caption (the marina's name). */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val caption: String? = null,
    /** MARINA only: the public attribution ("Foto: Marina ..."). */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val credit: String? = null,
    /** MARINA only: where the photo comes from (the credit's link). */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val sourceUrl: String? = null,
) {
    companion object {
        const val KIND_PARTNER = "PARTNER"
        const val KIND_LAYOUT = "LAYOUT"
        const val KIND_MODEL_SAMPLE = "MODEL_SAMPLE"
        const val KIND_MARINA = "MARINA"
    }
}
