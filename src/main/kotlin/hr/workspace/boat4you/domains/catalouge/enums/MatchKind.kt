package hr.workspace.boat4you.domains.catalouge.enums

/**
 * How the matched offer slot's REAL window relates to the customer's searched
 * period (Deploy 4). Lets the search card label honestly: EXACT shows no badge,
 * the others render "Closest week" with the real offerDateFrom/offerDateTo.
 *
 *  - EXACT:   slot from == searched start AND slot to == searched end
 *  - SHIFTED: same duration, different start (a genuinely nearby week)
 *  - SHORTER: slot duration < searched duration
 *  - LONGER:  slot duration > searched duration
 *
 * Null when the customer searched without dates (browse mode) or the slot has
 * no window (custom yacht).
 */
enum class MatchKind {
    EXACT,
    SHIFTED,
    SHORTER,
    LONGER,
}
