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
 * 4. an obligatory crew charge is CREWED, then an obligatory skipper / captain charge is SKIPPERED;
 * 5. otherwise BAREBOAT.
 *
 * Obligatory charges are the offer's obligatory rows plus the yacht's obligatory rows valid on the charter's first day
 * (what the price calculation charges), by name after [ExtraNameNormalizer] ("One man crew" reads "Skipper"). The
 * name must START with the role ("Skipper + food", "Professional Captain RYA/MCA ...", "3 crew: captain, cook,
 * deckhand"): partners put "skipper" inside unrelated obligatory rows - "Security Deposit* Valid ONLY for insured
 * skippers ...", "6% to added on the invoice when the skipper is hired", "Temporary Belize Skipper Certificate fee",
 * "Deckhand [Mandatory for Charters without our skipper ...]", "Starter Pack (... for each crew member)" (prod
 * 9.10.2026; with these rules 16,751 future offers / 321 boats have an obligatory skipper on the offer and 10,737 /
 * 258 more on the boat).
 */
object OfferCharterRules {
    private val CREWED_PRODUCTS = setOf(CharterType.CREWED, CharterType.ALL_INCLUSIVE, CharterType.CRUISE)

    private val SKIPPER = Regex("""^\W*(professional\s+)?(skipper|captain)""", RegexOption.IGNORE_CASE)

    /** A skipper's insurance, a fee or deposit about skippers, a licence / certificate, a check-out skipper. */
    private val NOT_SKIPPER = Regex("""insurance|additional fee|surcharge|certific|licen|check[- ]?out|deposit""", RegexOption.IGNORE_CASE)

    private val CREW = Regex("""^\W*(\d+\s*)?crew\b""", RegexOption.IGNORE_CASE)

    private val NOT_CREW = Regex("""crew\s*(list|change|member)""", RegexOption.IGNORE_CASE)

    fun isSkipperCharge(name: String?): Boolean {
        val n = ExtraNameNormalizer.normalize(name) ?: return false
        return SKIPPER.containsMatchIn(n) && !NOT_SKIPPER.containsMatchIn(n)
    }

    fun isCrewCharge(name: String?): Boolean {
        val n = ExtraNameNormalizer.normalize(name) ?: return false
        return CREW.containsMatchIn(n) && !NOT_CREW.containsMatchIn(n)
    }

    /**
     * @param product the offer's product; null when the card has no offer (custom boat, no matching offer row)
     * @param yachtTypes the boat's partner charter types (yacht_charter_type)
     * @param obligatoryCharges names of the obligatory charges of this offer (offer rows + valid yacht rows)
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
        if (noProduct && yachtTypes.any { it in CREWED_PRODUCTS } && CharterType.BAREBOAT !in yachtTypes) {
            return OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.CREWED_YACHT)
        }
        obligatoryCharges.firstOrNull { isCrewCharge(it) }?.let {
            return OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.OBLIGATORY_CREW, it.trim())
        }
        obligatoryCharges.firstOrNull { isSkipperCharge(it) }?.let {
            return OfferCharterDto(OfferCharterKind.SKIPPERED, OfferCharterBasis.OBLIGATORY_SKIPPER, it.trim())
        }
        return OfferCharterDto(OfferCharterKind.BAREBOAT, OfferCharterBasis.BAREBOAT)
    }
}
