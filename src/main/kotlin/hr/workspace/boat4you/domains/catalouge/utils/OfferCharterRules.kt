package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterBasis
import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterDto
import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterKind
import hr.workspace.boat4you.domains.catalouge.enums.CharterType

/**
 * Bareboat / Skippered / Crewed for ONE offer (admin Offers workspace, Mario 9.10.2026), in this order:
 *
 * 1. a gulet ([GuletRules]) is CREWED;
 * 2. the offer's product (MMK sends one per offer) CREWED / ALL_INCLUSIVE / CRUISE is CREWED;
 * 3. no product (NauSys offers carry UNKNOWN): a boat listed crewed and never bareboat is CREWED;
 * 4. the obligatory charges: a crew charge, or a skipper together with another crew member (two charges or one
 *    "Skipper & Chef"), is CREWED; a skipper alone is SKIPPERED; a hostess / cook / deckhand alone stays BAREBOAT and is
 *    named (the client still skippers);
 * 5. otherwise BAREBOAT (unconfirmed on a NauSys offer of a boat listed both bareboat and crewed).
 *
 * Obligatory charges are the offer's obligatory rows plus the yacht's obligatory rows the price calculation charges
 * (valid on the charter's first day and for the boat's home base), by name after [ExtraNameNormalizer] ("One man crew"
 * reads "Skipper"). Partners name these freely, and "skipper" turns up inside charges that are not one, so a role counts
 * only when the charge is ABOUT it (prod 9.10.2026, every obligatory name of future bareboat / NauSys offers):
 *
 * - the role word is singular ("Security Deposit* Valid ONLY for insured skippers ..." is not a skipper);
 * - nothing conditional: "6% to added on the invoice when the skipper is hired", "Deckhand [Mandatory for Charters
 *   without our skipper ...]", "Skipper (IN CASE THE CLIENT DOESN'T OWN A BOAT DRIVING LICENSE)";
 * - not after a deposit / waiver / insurance / licence / certificate / handover / invoice ("REQUIRED SAILING LICENSE:
 *   OFFSHORE YACHT SKIPPER ..."), nor after a fee / food / check-out / for / by ... in its own clause ("Additional fee
 *   for Skipper", "Checkout Skipper");
 * - not naming a thing right after the role ("Temporary Belize Skipper Certificate fee", "Skipper's liability
 *   insurance", "Captain's dinner", "Skipper meals", "Skipper training practice", "Skipper 1st Day Mandatory").
 *
 * What follows the role in its own brackets or after a "+" stays the skipper's ("Skipper + deposit insurance",
 * "Skipper (includes 300.00 EUR non-refundable deposit insurance)"), and the role need not come first ("Wintersailing
 * | Skipper | 2026 - 2027", "Saxdor - Obligatory daily Skipper", "Tour leader 53", "Crewed Skipper SEY").
 */
object OfferCharterRules {
    private val CREWED_PRODUCTS = setOf(CharterType.CREWED, CharterType.ALL_INCLUSIVE, CharterType.CRUISE)

    private val IC = RegexOption.IGNORE_CASE

    /** The skipper - one person - whatever the partner calls the role; singular only. */
    private val SKIPPER = Regex("""\b(skipper|skiper|captain|captian|capitan|capitano|kapitan|kapetan|tour\s*leader)\b""", IC)

    /** Anyone else on board for the guests. */
    private val CREW_MEMBER =
        Regex("""\b(hostess|host|cook|chef|deck\s*-?\s*hands?|sailors?|stewards?|stewardess|marinero|marinaio|mate|engineer|housekeeper|maid)\b""", IC)

    private val CREW = Regex("""\bcrew\b""", IC)

    /** "3 crew", "2 members of crew", "crew of 4", "a professional crew and full service of 5". */
    private val CREW_COUNT =
        Regex(
            """\b(\d+)\s*(?:x\s*)?(?:crew|members?\s+of\s+(?:the\s+)?crew)\b|\bcrew\s+(?:and\s+full\s+service\s+)?of\s+(?:of\s+)?(\d+)\b|\bfull\s+service\s+of\s+(?:of\s+)?(\d+)""",
            IC,
        )

    private val CONDITIONAL = Regex("""\b(in\s+case|if|when|unless|without|except)\b""", IC)

    /** Anywhere before the role: the charge is about something else. */
    private val BEFORE_ROLE = Regex("""deposit|waiver|insurance|licen[cs]e|certific|handover|invoice""", IC)

    private val CLAUSE_SEPARATOR = Regex("""[,;:|(\[+&/\-–—]""")

    /** In the role's own clause (since the last separator): the role is what the charge is for / of. */
    private val BEFORE_ROLE_IN_CLAUSE =
        Regex(
            """\b(fees?|surcharge|costs?|charge|food|meals?|provisions?|provisioning|check[- ]?(out|in)|checkout|checkin|training|transfers?|dinghy|tender|outboard|for|by|to|of|towels?|linen)\b""",
            IC,
        )

    /** Right after the role, same words: the role names a thing, not a person. */
    private val RIGHT_AFTER_ROLE =
        Regex(
            """^(?:'s|s'|’s)?\s+(certificat\w*|licen[cs]e\w*|liability|insurance|training|course|school|lesson|dinner|lunch|breakfast|food|meals?|provisions?|provisioning|accommodation|cabin|check[- ]?(out|in)|transfers?|list|fee\s+for|1st\s+day|first\s+day|hands?\s+in\s+charge)\b""",
            IC,
        )

    /** "crew list", "for each crew member", "crew provisions", "(Crew hands in charge)". */
    private val NOT_CREW =
        Regex(
            """crew\s*(list|change|transfer)|\b(for|per|of)\s+(each|every|the|all|any|our)?\s*crew\b|crew(?:'s|s'|’s)?\s+(provisions?|provisioning|food|meals?|cabin|accommodation|hands?\s+in\s+charge|insurance)""",
            IC,
        )

    private val WHITESPACE = Regex("""\s+""")

    /** What one obligatory charge puts on board. */
    enum class Role {
        /** A crew: "Crew", "3 crew: ...", "Skipper & Chef". */
        CREW,

        /** The skipper alone. */
        SKIPPER,

        /** A hostess / cook / deckhand ..., no skipper. */
        CREW_MEMBER,
    }

    private fun about(
        name: String,
        match: MatchResult,
    ): Boolean {
        val before = name.substring(0, match.range.first)
        if (BEFORE_ROLE.containsMatchIn(before)) return false
        if (BEFORE_ROLE_IN_CLAUSE.containsMatchIn(before.split(CLAUSE_SEPARATOR).last())) return false
        return !RIGHT_AFTER_ROLE.containsMatchIn(name.substring(match.range.last + 1))
    }

    /** The role of one obligatory charge by its partner name, null when it puts nobody on board. */
    fun roleOf(name: String?): Role? {
        val n = ExtraNameNormalizer.normalize(name)?.replace(WHITESPACE, " ")?.trim()
        if (n.isNullOrEmpty() || CONDITIONAL.containsMatchIn(n)) return null
        val count = CREW_COUNT.find(n)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        val skipper = SKIPPER.find(n)?.let { about(n, it) } == true
        val member = CREW_MEMBER.find(n)?.let { about(n, it) } == true
        val crew = !NOT_CREW.containsMatchIn(n) && CREW.find(n)?.let { about(n, it) } == true
        return when {
            count != null && count >= 2 -> Role.CREW
            skipper && member -> Role.CREW
            // "SURI'S CREW (SKIPPER)": a crew of one is the skipper
            skipper -> Role.SKIPPER
            crew -> Role.CREW
            member -> Role.CREW_MEMBER
            else -> null
        }
    }

    /**
     * @param product the offer's product; null when the card has no offer (custom boat, no matching offer row)
     * @param yachtTypes the boat's partner charter types (yacht_charter_type)
     * @param obligatoryCharges names of the obligatory charges of this offer (offer rows + charged yacht rows)
     */
    fun classify(
        gulet: Boolean,
        product: CharterType?,
        yachtTypes: Set<CharterType>,
        obligatoryCharges: List<String>,
    ): OfferCharterDto {
        if (gulet) return OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.GULET)
        if (product in CREWED_PRODUCTS) return OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.CREWED_PRODUCT)
        val noProduct = product == null || product == CharterType.UNKNOWN
        val listedCrewed = yachtTypes.any { it in CREWED_PRODUCTS }
        if (noProduct && listedCrewed && CharterType.BAREBOAT !in yachtTypes) {
            return OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.CREWED_YACHT)
        }
        val roles = obligatoryCharges.map { it.trim() to roleOf(it) }
        roles.firstOrNull { it.second == Role.CREW }?.let {
            return OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.OBLIGATORY_CREW, it.first)
        }
        val skipper = roles.firstOrNull { it.second == Role.SKIPPER }?.first
        val member = roles.firstOrNull { it.second == Role.CREW_MEMBER }?.first
        return when {
            skipper != null && member != null -> OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.OBLIGATORY_CREW, "$skipper + $member")
            skipper != null -> OfferCharterDto(OfferCharterKind.SKIPPERED, OfferCharterBasis.OBLIGATORY_SKIPPER, skipper)
            member != null -> OfferCharterDto(OfferCharterKind.BAREBOAT, OfferCharterBasis.OBLIGATORY_CREW_MEMBER, member)
            noProduct && listedCrewed -> OfferCharterDto(OfferCharterKind.BAREBOAT, OfferCharterBasis.BAREBOAT_UNCONFIRMED)
            else -> OfferCharterDto(OfferCharterKind.BAREBOAT, OfferCharterBasis.BAREBOAT)
        }
    }
}
