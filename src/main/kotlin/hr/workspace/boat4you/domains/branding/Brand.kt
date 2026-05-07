package hr.workspace.boat4you.domains.branding

import org.openapitools.model.LanguageEnum

/**
 * Per-brand context that drives email From line, recipient, logo, and
 * the public-site URL embedded in transactional links (e.g. inquiry
 * "Open page" → /boat/{slug}). Front-ends declare which brand they are
 * by sending the [BrandResolver.HEADER] HTTP header on requests that
 * end up triggering customer-facing email; the resolver maps that to a
 * [Brand] entry in [BrandRegistry] and the email service renders
 * accordingly.
 *
 * Adding a new brand: append a new entry to [BrandRegistry.byId]. The
 * front-end then needs to send `X-Boat4You-Brand: {id}` on inquiry /
 * booking requests. Logo file goes under
 * `src/main/resources/data/images/` and the [logoMarkClasspath] points
 * at it.
 */
data class Brand(
    /** Stable identifier the front-end sends in the header. Lower-kebab. */
    val id: String,
    /** From-line display name (everything before the `<email>`). */
    val displayName: String,
    /** Slogan / tagline used in headers. Optional — falls back to [displayName]. */
    val tagline: String? = null,
    /** Visible From address. SMTP login still happens with the global
     *  `MAIL_USERNAME`; this only changes the inbox label. */
    val fromAddress: String,
    /** Mailbox that receives broker-facing notifications (new inquiry,
     *  booking confirmation cc, …). */
    val recipientAddress: String,
    /** Public URL — embedded in "Open page →" links and footer "Source"
     *  attribution. No trailing slash. */
    val websiteUrl: String,
    /** Customer-support email shown in email footer. Often equal to
     *  [recipientAddress] but kept separate for brands that route
     *  internal vs external mailboxes. */
    val supportEmail: String,
    /** Support phone, in international format. Null = footer omits the
     *  phone row. */
    val supportPhone: String? = null,
    /** Classpath path to the logo PNG used as `cid:brandLogoMark` inline
     *  attachment. Square mark, ~512×512. */
    val logoMarkClasspath: String,
    /** Hex accent colour ("#ffd24a") for hero card highlights. Defaults
     *  to Boat4You amber if a brand hasn't picked its own yet. */
    val accentColor: String = "#ffd24a",
    /** Language used for internal-team email rendered for THIS brand —
     *  notably the inquiry-notification email read by Mario / agents.
     *  Catamaran-Croatia → HR (Croatian admin), Catamaran-Italy → IT,
     *  rest default to EN until per-brand staff confirm a preference.
     *  Customer-facing emails use the recipient's `user.language`, NOT
     *  this field. */
    val defaultLanguage: LanguageEnum = LanguageEnum.EN,
)
