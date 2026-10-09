package hr.workspace.boat4you.domains.catalouge.dto

data class YachtImageDto(
    val id: Long?,
    val position: Short? = null,
    val mainImage: Boolean? = null,
    /** The boat's layout (deck / floor plan), read from the file name: [hr.workspace.boat4you.domains.catalouge.utils.LayoutImageRules]. */
    val layout: Boolean = false,
)
