package hr.workspace.boat4you.domains.catalouge.dto

/**
 * ADMIN ONLY (Offers workspace, Mario 9.10.2026): how the offer a search card shows is chartered, so the broker sees
 * Bareboat / Skippered / Crewed on every row before adding it to a client offer. Built per page for SYSTEM_ADMIN
 * searches only (YachtQueryingService.fetchOfferCharters, classified by OfferCharterRules); never in a public answer.
 */
data class OfferCharterDto(
    val kind: OfferCharterKind,
    val basis: OfferCharterBasis,
    /**
     * The partner's name of the obligatory charge behind OBLIGATORY_SKIPPER, OBLIGATORY_CREW ("Skipper + Hostess" when a
     * skipper and another crew member are two charges) or OBLIGATORY_CREW_MEMBER, else null.
     */
    val obligatoryExtra: String? = null,
)

enum class OfferCharterKind {
    /** A bareboat product without an obligatory skipper or crew (an obligatory hostess / cook alone stays here). */
    BAREBOAT,

    /** A bareboat product whose skipper (or captain) - and nobody else - is an obligatory charge. */
    SKIPPERED,

    /** A crewed product, a crew-only boat, an obligatory crew (a skipper and anyone else), or any gulet. */
    CREWED,
}

enum class OfferCharterBasis {
    /** A gulet - never bareboat (GuletRules). */
    GULET,

    /** The offer's product is CREWED / ALL_INCLUSIVE / CRUISE (MMK). */
    CREWED_PRODUCT,

    /** No product on the offer (NauSys) and the boat is listed crewed, never bareboat. */
    CREWED_YACHT,

    /**
     * An obligatory crew: a crew charge ("Crew - included", "3 crew: captain, cook, deckhand", "Skipper & Chef") or an
     * obligatory skipper together with an obligatory hostess / cook / deckhand charge.
     */
    OBLIGATORY_CREW,

    /** An obligatory skipper / captain charge, nobody else. */
    OBLIGATORY_SKIPPER,

    /** BAREBOAT with an obligatory hostess / cook / deckhand but no skipper: the client still skippers. */
    OBLIGATORY_CREW_MEMBER,

    /**
     * BAREBOAT as far as the data tells: no product on the offer (NauSys) on a boat listed both bareboat and crewed, and
     * no obligatory skipper or crew - worth confirming with the partner.
     */
    BAREBOAT_UNCONFIRMED,

    /** None of the above. */
    BAREBOAT,
}
