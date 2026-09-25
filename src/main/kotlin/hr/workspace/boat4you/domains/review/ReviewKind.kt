package hr.workspace.boat4you.domains.review

/**
 * BOOKING = the Boat4You booking experience (asked after the first payment); YACHT = the boat / charter (asked
 * 3 days after the charter ends). Each kind has its own optional 1-5 sub-scores, keyed by the names the web form
 * sends in `scores`.
 */
enum class ReviewKind(
    val scoreKeys: List<String>,
) {
    BOOKING(listOf("easeOfBooking", "communication", "valueTransparency")),
    YACHT(listOf("boatCondition", "cleanliness", "checkInOut", "charterCompany", "value")),
}

/** NEW until an admin moderates it; nothing is auto-published (owner decision, 25.9.2026). */
enum class ReviewStatus {
    NEW,
    PUBLISHED,
    HIDDEN,
}
