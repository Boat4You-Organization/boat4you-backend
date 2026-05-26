package hr.workspace.boat4you.domains.catalouge.services

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import hr.workspace.boat4you.common.services.toLocale
import hr.workspace.boat4you.domains.branding.Brand
import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the variables map + dispatches the "new charter inquiry"
 * notification email. The render is a Thymeleaf template at
 * `templates/email/inquiryNotification.html` that mirrors the design Mario
 * approved (navy hero card, client / yacht / dates breakdown, amber
 * message card).
 *
 * Multi-brand: every entry point takes a [Brand] (resolved by
 * `BrandResolver` from the request header). The brand drives the logo,
 * From-line, recipient mailbox, "Open page" host, and the support
 * contact strip in the email body — so a customer who came in through
 * catamaran-croatia-charter.com sees that brand in the inbox, not the
 * shared Boat4You shell.
 */
@Service
class InquiryEmailService(
    private val emailService: EmailService,
    private val inquiryRepository: InquiryRepository,
    private val brandRegistry: BrandRegistry,
    private val messageSource: MessageSource,
) {
    private val log = LoggerFactory.getLogger(InquiryEmailService::class.java)

    private val phoneUtil = PhoneNumberUtil.getInstance()

    /** Display zone for the "Received …" header. Boat4You is HQ-ed in
     *  Croatia and inquiries are read by Mario / agents on local time, so
     *  we render every timestamp in Europe/Zagreb. The zone handles
     *  CET/CEST (+1/+2) automatically. */
    private val displayZone: ZoneId = ZoneId.of("Europe/Zagreb")

    // Date formatters resolved per-locale so day-of-week / month names
    // render in the brand's admin language (HR brand → "Sub, 20 lis 2026"
    // not "Sat, 20 Jun 2026"). EN fallback kept as the baseline used by
    // tests + the formatInquiryNumber path that doesn't carry a Locale.
    private val receivedFormatterEn = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.ENGLISH)
    private fun receivedFormatter(locale: Locale) = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", locale)
    private fun longDateFormatter(locale: Locale) = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)
    private fun shortDateFormatter(locale: Locale) = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    private fun shortDateNoYearFormatter(locale: Locale) = DateTimeFormatter.ofPattern("d MMM", locale)

    /** Cache of `data:image/png;base64,…` URLs keyed by classpath path —
     *  preview rendering reuses these instead of re-reading the PNG off
     *  disk for every request. Real SMTP delivery uses `cid:` so doesn't
     *  hit this cache. */
    private val logoDataUrlCache = ConcurrentHashMap<String, String>()

    @Transactional(readOnly = true)
    fun sendNewInquiryNotification(
        inquiry: Inquiry,
        brand: Brand? = null,
        force: Boolean = false,
    ) {
        // Default args that reference an injected dependency (`brandRegistry`)
        // can't go on the parameter — Kotlin compiles default expressions
        // into a static `*$default` helper and Spring's CGLIB proxy +
        // reflection invocation path enters that helper without a `this`
        // bound to the proxy, producing NPE on the dependency. Resolve
        // inside the body instead.
        val resolvedBrand = brand ?: brandRegistry.default
        // Brand drives the language for inquiry email — admin/team reading
        // the inquiry sees their preferred language. Customer-facing emails
        // use user.language instead. Mario rule (3.5.2026): inquiry email
        // localised so per-brand admins can read in their own language.
        val locale = resolvedBrand.defaultLanguage.toLocale()
        val variables = buildVariables(inquiry, resolvedBrand, locale, logoSrc = "cid:$BRAND_LOGO_CID")
        emailService.sendEmail(
            recipients = listOf(resolvedBrand.recipientAddress),
            subject = buildSubject(inquiry, resolvedBrand, locale),
            templateName = TEMPLATE,
            variables = variables,
            // Reply-To = the lead's email so hitting "Reply" in Mario's
            // inbox drafts straight to the customer, no copy-paste.
            replyTo = inquiry.email,
            force = force,
            extraInlineImages = mapOf(BRAND_LOGO_CID to loadBrandLogo(resolvedBrand)),
            fromOverride = "${resolvedBrand.displayName} <${resolvedBrand.fromAddress}>",
            locale = locale,
        )
        log.info(
            "Queued new-inquiry notification email for inquiry id={} brand={} to {} (force={})",
            inquiry.id,
            resolvedBrand.id,
            resolvedBrand.recipientAddress,
            force,
        )
    }

    /** Loads inquiry by id and sends the notification with `force = true`.
     *  Lets Mario hit a real SMTP delivery from the dev profile without
     *  enabling email globally. */
    @Transactional(readOnly = true)
    fun sendNewInquiryNotificationById(
        inquiryId: Long,
        brand: Brand? = null,
        force: Boolean = false,
    ) {
        val inquiry = inquiryRepository
            .findById(inquiryId)
            .orElseThrow { IllegalArgumentException("Inquiry $inquiryId not found") }
        sendNewInquiryNotification(inquiry, brand, force = force)
    }

    /** Render the template to HTML without sending — used by the preview
     *  endpoint so Mario can review the layout in a browser. Requires a
     *  Hibernate session because the variables map walks lazy associations
     *  on the inquiry (yacht → model → manufacturer, yacht → location,
     *  yacht → yachtImages). */
    @Transactional(readOnly = true)
    fun renderPreview(inquiryId: Long, brand: Brand? = null): String {
        val resolvedBrand = brand ?: brandRegistry.default
        val inquiry = inquiryRepository
            .findById(inquiryId)
            .orElseThrow { IllegalArgumentException("Inquiry $inquiryId not found") }
        // Browser previews can't resolve `cid:*` inline attachments, so we
        // inline the logo as a data URL instead. Real SMTP sends keep using
        // `cid:` to avoid bloating each delivered message with the same PNG.
        // NB: `renderTemplate` doesn't currently take a Locale parameter, so
        // preview falls back to MessageSource's default (English baseline).
        // Acceptable for the preview-in-browser dev story; live delivery
        // honours the brand's defaultLanguage via `sendNewInquiryNotification`.
        val locale = resolvedBrand.defaultLanguage.toLocale()
        return emailService.renderTemplate(
            TEMPLATE,
            buildVariables(inquiry, resolvedBrand, locale, logoSrc = brandLogoDataUrl(resolvedBrand)),
        )
    }

    private fun buildSubject(inquiry: Inquiry, brand: Brand, locale: Locale): String {
        val name = listOfNotNull(inquiry.name, inquiry.surname).joinToString(" ").ifBlank { "Guest" }
        val yachtName = inquiry.yacht?.name ?: "—"
        // Localised pattern: `inquiry.subject = New inquiry — {0} → {1}` etc.
        // Brand short label is used as a prefix flag so admins can filter
        // multiple brands' inquiries in one inbox.
        val shortBrand = brand.displayName.substringBefore('|').substringBefore(',').trim()
        val core = messageSource.getMessage("inquiry.subject", arrayOf<Any>(name, yachtName), locale)
        return "[$shortBrand] $core"
    }

    private fun buildVariables(
        inquiry: Inquiry,
        brand: Brand,
        locale: Locale,
        logoSrc: String,
    ): Map<String, Any?> {
        val createdAt = inquiry.createdAt ?: LocalDateTime.now()
        val firstName = inquiry.name.orEmpty().trim()
        val lastName = inquiry.surname.orEmpty().trim()
        val fullName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Guest" }

        // Country derived from the phone's E.164 prefix when possible (the
        // form doesn't ask the client to pick a country explicitly). Falls
        // back to null so the template renders an em-dash.
        val countryCode = inquiry.phone?.let(::regionCodeFromPhone)
        val countryName = countryCode?.let { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH) }
            ?.takeIf { it.isNotBlank() }
        val countryFlag = countryCode?.let(::countryCodeToFlagEmoji)

        val yacht = inquiry.yacht
        val yachtManufacturer = yacht?.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModel = yacht?.model?.name
        val yachtName = yacht?.name
        // "Manufacturer Model" e.g. "Beneteau Oceanis 46.1" — used as the
        // primary line in the requested-yacht card. Falls back gracefully
        // if either piece is missing.
        val yachtModelLabel = listOfNotNull(yachtManufacturer, yachtModel)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { yachtModel ?: "—" }
        // Full identifier "Manufacturer Model Name" e.g. "Beneteau Oceanis
        // 46.1 Tria" — used in the hero title so the recipient sees the
        // exact yacht class + name, not just the brand-given moniker.
        // Mario rule (3.5.2026): emails always show model + name, never
        // bare name on its own.
        val yachtFullLabel = listOfNotNull(yachtManufacturer, yachtModel, yachtName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { yachtName ?: "—" }
        val yachtLocationName = yacht?.location?.name
        // `Location.countryCode` is already an ISO-2 string (e.g. "HR") in
        // our schema — no need to map from partner ids.
        val yachtLocationCountryCode = yacht?.location?.countryCode
            ?: yacht?.location?.country?.name?.let(::countryNameToIsoCode)
        val yachtLocationFlag = yachtLocationCountryCode?.let(::countryCodeToFlagEmoji)

        // Yacht thumbnail. We only embed the image URL when the yacht has a
        // synced binary (rare in dev — see the "we'll pull images on prod"
        // decision). Template handles the null case with a placeholder.
        val mainImageId = yacht?.yachtImages
            ?.let { images -> images.firstOrNull { it.mainImage == true } ?: images.firstOrNull() }
            ?.id
        val yachtImageUrl = mainImageId?.let { "${brand.websiteUrl}/public/image/$it?width=160" }

        // Yacht detail page on the brand's customer site (catamaran-croatia
        // -charter.com/boat/… for that brand, boat4you.com/boat/… for
        // boat4you, etc.). Falls back to the brand's home page when no
        // yacht is attached.
        val yachtPageUrl = if (yacht != null) {
            val slug = SlugUtils.toSlugWithId(
                manufacturerName = yacht.model?.manufacturer?.name,
                modelName = yacht.model?.name,
                yachtName = yacht.name,
                yachtId = yacht.id!!,
            )
            "${brand.websiteUrl}/boat/$slug"
        } else {
            brand.websiteUrl
        }

        val nights = nightsBetween(inquiry.dateFrom, inquiry.dateTo)
        // Pre-render the localised "{n} night(s)" label so the template
        // doesn't have to do plural choice in EL — keeps the .properties
        // bundles authoritative for grammar (HR/PL use distinct plural
        // forms; the simple ternary in the old template would mis-render).
        val nightsKey = if (nights == 1L) "inquiry.nightsOne" else "inquiry.nightsOther"
        val nightsLabel = messageSource.getMessage(nightsKey, arrayOf<Any>(nights), locale)
        val setSailDate = inquiry.dateFrom?.format(longDateFormatter(locale))
        val returnDate = inquiry.dateTo?.format(longDateFormatter(locale))
        val dateRangeShort = if (inquiry.dateFrom != null && inquiry.dateTo != null) {
            val sameYear = inquiry.dateFrom!!.year == inquiry.dateTo!!.year
            val left = if (sameYear) inquiry.dateFrom!!.format(shortDateNoYearFormatter(locale))
            else inquiry.dateFrom!!.format(shortDateFormatter(locale))
            "$left – ${inquiry.dateTo!!.format(shortDateFormatter(locale))}"
        } else null

        // "New client" pill — true when no earlier inquiry from this email
        // exists. Cheap query (indexed on email) and only happens once per
        // submission.
        val isNewClient = inquiry.email?.let { email ->
            val priorCount = inquiryRepository.countByEmailIgnoreCaseAndIdNot(email, inquiry.id ?: -1L)
            priorCount == 0L
        } ?: true

        // Source label / website host shown in footer ("Source: {host} /
        // yacht page"). Strip protocol + www. for a cleaner caption.
        val brandHost = brand.websiteUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')

        val brandShortName = brand.displayName.substringBefore('|').substringBefore(',').trim()

        return mapOf(
            "logoSrc" to logoSrc,
            "brandShortName" to brandShortName,
            "brandDisplayName" to brand.displayName,
            "brandTagline" to (brand.tagline ?: ""),
            "brandWebsiteUrl" to brand.websiteUrl,
            "brandHost" to brandHost,
            "brandSupportEmail" to brand.supportEmail,
            "brandSupportPhone" to brand.supportPhone,
            "brandAccentColor" to brand.accentColor,
            "inquiryNumber" to formatInquiryNumber(inquiry.id, createdAt),
            "receivedAt" to formatReceivedAt(createdAt, locale),
            "currentYear" to createdAt.year.toString(),
            "clientFullName" to fullName,
            "clientFirstName" to firstName.ifBlank { "—" },
            "clientLastName" to lastName.ifBlank { "—" },
            "clientCountry" to countryName,
            "clientCountryFlag" to countryFlag,
            "clientEmail" to inquiry.email,
            "clientPhone" to inquiry.phone,
            "isNewClient" to isNewClient,
            "yachtModel" to (yachtModel ?: "—"),
            "yachtModelLabel" to yachtModelLabel,
            "yachtFullLabel" to yachtFullLabel,
            "yachtName" to (yachtName ?: "—"),
            "yachtImageUrl" to yachtImageUrl,
            "yachtPageUrl" to yachtPageUrl,
            "yachtLocation" to yachtLocationName,
            "yachtLocationFlag" to yachtLocationFlag,
            "setSailDate" to (setSailDate ?: "—"),
            "returnDate" to (returnDate ?: "—"),
            "nightsCount" to nights,
            "nightsLabel" to nightsLabel,
            "dateRangeShort" to dateRangeShort,
            "clientMessage" to inquiry.message,
            "clientMessageHtml" to inquiry.message?.let(::escapeAndLinebreak),
            // Legacy keys kept for any template still using them.
            "publicUrl" to brand.websiteUrl,
            "sourceLabel" to (yacht?.let { "$brandHost / yacht page" } ?: brandHost),
        )
    }

    private fun loadBrandLogo(brand: Brand): Resource = ClassPathResource(brand.logoMarkClasspath)

    private fun brandLogoDataUrl(brand: Brand): String =
        logoDataUrlCache.computeIfAbsent(brand.logoMarkClasspath) { path ->
            val bytes = ClassPathResource(path).inputStream.use { it.readBytes() }
            "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
        }

    private fun regionCodeFromPhone(rawPhone: String): String? {
        val phone = rawPhone.trim()
        if (phone.isBlank()) return null
        return try {
            val parsed = phoneUtil.parse(if (phone.startsWith("+")) phone else "+$phone", null)
            phoneUtil.getRegionCodeForNumber(parsed)?.takeIf { it.isNotBlank() && it != "ZZ" }
        } catch (e: NumberParseException) {
            null
        }
    }

    /** ISO-2 letters → regional indicator emoji (e.g. HR → 🇭🇷). */
    private fun countryCodeToFlagEmoji(code: String): String? {
        val upper = code.uppercase()
        if (upper.length != 2 || !upper.all { it in 'A'..'Z' }) return null
        val base = 0x1F1E6
        return String(Character.toChars(base + (upper[0] - 'A'))) +
            String(Character.toChars(base + (upper[1] - 'A')))
    }

    private fun countryNameToIsoCode(name: String): String? {
        val needle = name.trim().lowercase()
        return Locale.getISOCountries().firstOrNull { iso ->
            Locale.Builder().setRegion(iso).build().getDisplayCountry(Locale.ENGLISH).lowercase() == needle
        }
    }

    private fun nightsBetween(from: LocalDate?, to: LocalDate?): Long {
        if (from == null || to == null) return 0
        return ChronoUnit.DAYS.between(from, to).coerceAtLeast(0)
    }

    /** Format the inquiry id as "INQ-{yy}-{seq}" — e.g. the third inquiry
     *  in 2026 → "INQ-26-003". The sequence resets every January 1st;
     *  it's computed by counting inquiries with the same `created_at`
     *  year up to and including this row's id. Padded to 3 digits per
     *  Mario's spec — covers up to 999 inquiries/year, which is well
     *  above realistic broker volume. If we ever blow past that, the
     *  format gracefully widens (`%03d` only enforces a minimum). */
    private fun formatInquiryNumber(id: Long?, createdAt: LocalDateTime): String {
        val twoDigitYear = createdAt.year % 100
        val sequence = if (id != null) {
            val startOfYear = LocalDateTime.of(createdAt.year, 1, 1, 0, 0)
            val startOfNextYear = LocalDateTime.of(createdAt.year + 1, 1, 1, 0, 0)
            inquiryRepository.countInYearUpToId(startOfYear, startOfNextYear, id)
        } else {
            // Defensive — rendering the email for a row that hasn't been
            // saved yet should never happen, but if it does we surface "001"
            // so the format stays valid rather than crashing.
            1L
        }
        return "INQ-%02d-%03d".format(twoDigitYear, sequence)
    }

    /** Render the timestamp in Europe/Zagreb, suffixed with the dynamic
     *  GMT offset (CET = "GMT+1", CEST = "GMT+2"). Inquiry.createdAt is
     *  stored as a naive `LocalDateTime` already on the JVM's local TZ,
     *  so we attach the zone before formatting — no double conversion. */
    private fun formatReceivedAt(createdAt: LocalDateTime, locale: Locale = Locale.ENGLISH): String {
        val zoned = createdAt.atZone(displayZone)
        val offsetHours = zoned.offset.totalSeconds / 3600
        val sign = if (offsetHours >= 0) "+" else "-"
        val gmt = "GMT$sign${kotlin.math.abs(offsetHours)}"
        return "${zoned.format(receivedFormatter(locale))} ($gmt)"
    }

    private fun escapeAndLinebreak(raw: String): String {
        val escaped = raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
        return escaped.replace("\n", "<br />")
    }

    companion object {
        private const val TEMPLATE = "email/inquiryNotification"
        /** Content id used by the template to reference the brand logo
         *  inline image attachment (`<img src="cid:brandLogoMark">`). */
        private const val BRAND_LOGO_CID = "brandLogoMark"
    }
}
