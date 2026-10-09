package hr.workspace.boat4you.domains.catalouge.dto

/**
 * ADMIN ONLY (Offers workspace, Mario 9.10.2026): how the offer a search card shows is chartered, so the broker sees
 * Bareboat / Skippered / Crewed on every row before adding it to a client offer. Built per page for SYSTEM_ADMIN
 * searches only (YachtQueryingService.fetchOfferCharters, classified by OfferCharterRules); never in a public answer.
 */
data class OfferCharterDto(
    val kind: OfferCharterKind,
    val basis: OfferCharterBasis,
    /** The partner's name of the obligatory skipper / crew charge behind SKIPPERED or OBLIGATORY_CREW, else null. */
    val obligatoryExtra: String? = null,
)

enum class OfferCharterKind {
    /** A bareboat product without an obligatory skipper or crew. */
    BAREBOAT,

    /** A bareboat product whose skipper (or captain) is an obligatory charge. */
    SKIPPERED,

    /** A crewed product, a crew-only boat, an obligatory crew charge, or any gulet. */
    CREWED,
}

enum class OfferCharterBasis {
    /** A gulet - never bareboat (GuletRules). */
    GULET,

    /** The offer's product is CREWED / ALL_INCLUSIVE / CRUISE (MMK). */
    CREWED_PRODUCT,

    /** No product on the offer (NauSys) and the boat is listed crewed, never bareboat. */
    CREWED_YACHT,

    /** An obligatory crew charge ("Crew - included", "3 crew: captain, cook, deckhand"). */
    OBLIGATORY_CREW,

    /** An obligatory skipper / captain charge. */
    OBLIGATORY_SKIPPER,

    /** None of the above. */
    BAREBOAT,
}
