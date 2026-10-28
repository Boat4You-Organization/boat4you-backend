package hr.workspace.boat4you.domains.contactcenter.service

/** Plain bounded text for everything the tracker stores: no control or line-separator characters, trimmed, clipped. */
object TrackerText {
    private val UNSAFE = Regex("[\\p{Cc}\\p{Zl}\\p{Zp}]")

    /** [raw] without control characters and line or paragraph separators, trimmed, at most [max] characters; null when nothing is left. */
    fun clip(
        raw: String?,
        max: Int,
    ): String? =
        raw
            ?.replace(UNSAFE, "")
            ?.trim()
            ?.take(max)
            ?.takeIf { it.isNotEmpty() }
}
