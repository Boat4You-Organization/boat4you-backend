package hr.workspace.boat4you.domains.pr.service

import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppression
import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppressionRepository
import hr.workspace.boat4you.domains.pr.dto.ImportResult
import hr.workspace.boat4you.domains.pr.dto.PrContactDto
import hr.workspace.boat4you.domains.pr.dto.PrContactFilter
import hr.workspace.boat4you.domains.pr.dto.PrContactRequest
import hr.workspace.boat4you.domains.pr.dto.PrOutletDto
import hr.workspace.boat4you.domains.pr.dto.PrOutletRequest
import hr.workspace.boat4you.domains.pr.jpa.PrContact
import hr.workspace.boat4you.domains.pr.jpa.PrContactRepository
import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import hr.workspace.boat4you.domains.pr.jpa.PrOutlet
import hr.workspace.boat4you.domains.pr.jpa.PrOutletRepository
import hr.workspace.boat4you.domains.pr.rules.PrComplianceRules
import jakarta.persistence.criteria.Predicate
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.reflect.KMutableProperty0

/**
 * Outlets and journalist contacts: admin CRUD, the CSV import of a contact wave, opt-out / bounce and the 24-month
 * retention. Every stored email is PrComplianceRules.normaliseEmail(raw), and an opt-out or bounce of one contact
 * blocks every contact with that address and puts it on the shared email_suppression list (never deleted here).
 * OPTED_OUT is final: the admin cannot set or leave it, and an import never touches a contact's status.
 */
@Service
@Suppress("TooManyFunctions") // one function per admin action, plus the import and the retention job
class PrContactService(
    private val outlets: PrOutletRepository,
    private val contacts: PrContactRepository,
    private val suppressions: EmailSuppressionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ---- Outlets ----

    @Transactional(readOnly = true)
    fun listOutlets(): List<PrOutletDto> = outlets.findAll(Sort.by("market", "name")).map { it.toDto() }

    @Transactional
    fun createOutlet(req: PrOutletRequest): PrOutletDto {
        val name = requiredOutletName(req.name)
        val market = market(req.market) ?: throw PrException.badRequest("MARKET_REQUIRED", "Market is required (ISO code such as GB)")
        val tier = tier(req.tier ?: throw PrException.badRequest("TIER_INVALID", "Tier is required (1, 2 or 3)"))
        if (outlets.findFirstByNameIgnoreCaseAndMarket(name, market) != null) throw outletExists(name, market)
        val outlet =
            PrOutlet().apply {
                this.name = name
                this.market = market
                this.tier = tier
                sectionUrl = req.sectionUrl.clean(URL_SHORT)
                deskContact = req.deskContact.clean(DESK_CONTACT)
                notes = req.notes.clean()
            }
        return outlets.save(outlet).toDto()
    }

    @Transactional
    fun updateOutlet(
        id: Long,
        req: PrOutletRequest,
    ): PrOutletDto {
        val outlet = outlet(id)
        val name = req.name?.let { requiredOutletName(it) } ?: outlet.name
        val market = req.market?.let { market(it) ?: throw PrException.badRequest("MARKET_REQUIRED", "Market cannot be empty") } ?: outlet.market!!
        // Checked before any field changes: a query would flush a clashing change into a unique-index error.
        val clash = outlets.findFirstByNameIgnoreCaseAndMarket(name, market)
        if (clash != null && clash.id != outlet.id) throw outletExists(name, market)
        outlet.name = name
        outlet.market = market
        req.tier?.let { outlet.tier = tier(it) }
        req.sectionUrl?.let { outlet.sectionUrl = it.clean(URL_SHORT) }
        req.deskContact?.let { outlet.deskContact = it.clean(DESK_CONTACT) }
        req.notes?.let { outlet.notes = it.clean() }
        return outlets.save(outlet).toDto()
    }

    // ---- Contacts ----

    @Transactional(readOnly = true)
    fun listContacts(filter: PrContactFilter): Page<PrContactDto> {
        val market = market(filter.market)
        val q = filter.q?.trim()?.lowercase()?.ifEmpty { null }
        val spec =
            Specification<PrContact> { root, query, cb ->
                val where = mutableListOf<Predicate>()
                market?.let { where += cb.equal(root.get<String>("market"), it) }
                filter.status?.let { where += cb.equal(root.get<PrContactStatus>("status"), it) }
                if (q != null) {
                    val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                    val outletIds = requireNotNull(query).subquery(Long::class.javaObjectType)
                    val o = outletIds.from(PrOutlet::class.java)
                    outletIds.select(o.get("id")).where(cb.like(cb.lower(o.get("name")), like, '\\'))
                    where +=
                        cb.or(
                            cb.like(cb.lower(root.get("email")), like, '\\'),
                            cb.like(cb.lower(root.get("name")), like, '\\'),
                            cb.like(cb.lower(root.get("role")), like, '\\'),
                            root.get<Long>("outletId").`in`(outletIds),
                        )
                }
                cb.and(*where.toTypedArray())
            }
        val pageable = PageRequest.of(filter.page.coerceAtLeast(0), filter.size.coerceIn(1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "id"))
        val page = contacts.findAll(spec, pageable)
        val names = outlets.findAllById(page.content.mapNotNull { it.outletId }.toSet()).associate { it.id to it.name }
        return page.map { it.toDto(names[it.outletId]) }
    }

    @Transactional
    fun createContact(req: PrContactRequest): PrContactDto {
        val email = email(req.email ?: "")
        val outlet = req.outletId?.let { outlet(it) }
        val status = req.status?.let { settable(it) } ?: PrContactStatus.ACTIVE
        if (contacts.findFirstByEmailIgnoreCaseAndOutletId(email, outlet?.id) != null) throw contactExists(email)
        val contact =
            PrContact().apply {
                outletId = outlet?.id
                this.email = email
                market = market(req.market) ?: outlet?.market
                name = req.name.clean(NAME)
                role = req.role.clean(NAME)
                emailSourceUrl = req.emailSourceUrl.clean(URL)
                emailFoundOn = req.emailFoundOn
                recentArticleUrl = req.recentArticleUrl.clean(URL)
                notes = req.notes.clean()
                this.status = if (suppressions.existsByEmailIgnoreCase(email)) PrContactStatus.OPTED_OUT else status
            }
        return contacts.save(contact).toDto(outlet?.name)
    }

    @Transactional
    fun updateContact(
        id: Long,
        req: PrContactRequest,
    ): PrContactDto {
        val contact = contact(id)
        req.status?.let {
            if (contact.status == PrContactStatus.OPTED_OUT) {
                throw PrException.conflict("CONTACT_OPTED_OUT", "This contact opted out or bounced; the status cannot change")
            }
            settable(it)
        }
        val email = req.email?.let { email(it) } ?: contact.email
        val outletId = req.outletId?.let { outlet(it).id } ?: contact.outletId
        val clash = contacts.findFirstByEmailIgnoreCaseAndOutletId(email, outletId)
        if (clash != null && clash.id != contact.id) throw contactExists(email)
        val newAddress = email != contact.email
        contact.email = email
        contact.outletId = outletId
        req.market?.let { contact.market = market(it) }
        req.name?.let { contact.name = it.clean(NAME) }
        req.role?.let { contact.role = it.clean(NAME) }
        req.emailSourceUrl?.let { contact.emailSourceUrl = it.clean(URL) }
        req.emailFoundOn?.let { contact.emailFoundOn = it }
        req.recentArticleUrl?.let { contact.recentArticleUrl = it.clean(URL) }
        req.notes?.let { contact.notes = it.clean() }
        req.status?.let { contact.status = it }
        if (newAddress && suppressions.existsByEmailIgnoreCase(email)) contact.status = PrContactStatus.OPTED_OUT
        return contacts.save(contact).toDto(outletName(contact.outletId))
    }

    /** Opt-out: OPTED_OUT on every contact with this address (any outlet) and one email_suppression row. Idempotent. */
    @Transactional
    fun optOut(
        contactId: Long,
        reason: String = "MANUAL",
    ): PrContactDto {
        val contact = contact(contactId)
        val email = PrComplianceRules.normaliseEmail(contact.email)
        val all = contacts.findAllByEmailIgnoreCase(email)
        all.forEach { it.status = PrContactStatus.OPTED_OUT }
        if (!suppressions.existsByEmailIgnoreCase(email)) {
            suppressions.save(
                EmailSuppression().apply {
                    this.email = email
                    this.reason = reason
                },
            )
        }
        log.info("PR contact {} {}: {} contact(s) with its address now OPTED_OUT", contactId, reason, all.size)
        return contact.toDto(outletName(contact.outletId))
    }

    /** A hard bounce: the same as an opt-out, with suppression reason BOUNCE. */
    @Transactional
    fun markBounced(contactId: Long): PrContactDto = optOut(contactId, "BOUNCE")

    // ---- CSV import (columns of _digital-pr/kontakti-val1-2026-11.csv; extra columns are ignored) ----

    @Transactional
    fun importCsv(csv: String): ImportResult {
        val records =
            try {
                PrCsv.parse(csv)
            } catch (e: IllegalArgumentException) {
                throw PrException.badRequest("CSV_INVALID", e.message ?: "Not a valid CSV file")
            }
        val header = records.firstOrNull()?.map { it.trim().lowercase() } ?: throw PrException.badRequest("CSV_INVALID", "The file is empty")
        val missing = REQUIRED_COLUMNS.filterNot { it in header }
        if (missing.isNotEmpty()) throw PrException.badRequest("CSV_INVALID", "Missing columns: ${missing.joinToString()}")
        var created = 0
        var updated = 0
        val skipped = mutableListOf<String>()
        records.drop(1).forEachIndexed { index, values ->
            val row = header.indices.associate { header[it] to values.getOrElse(it) { "" }.trim() }
            when (val outcome = importRow(row)) {
                RowOutcome.Created -> created++
                RowOutcome.Updated -> updated++
                RowOutcome.Unchanged -> Unit
                // Spreadsheet numbering: the header is row 1.
                is RowOutcome.Skipped -> skipped += "row ${index + 2}: ${outcome.reason}"
            }
        }
        log.info("PR contact import: {} created, {} updated, {} skipped", created, updated, skipped.size)
        return ImportResult(created, updated, skipped)
    }

    private sealed interface RowOutcome {
        data object Created : RowOutcome

        data object Updated : RowOutcome

        data object Unchanged : RowOutcome

        data class Skipped(
            val reason: String,
        ) : RowOutcome
    }

    @Suppress("ReturnCount") // one early return per skip reason
    private fun importRow(row: Map<String, String>): RowOutcome {
        val col = { name: String -> row[name].orEmpty() }
        // An unknown market (e.g. "Croatia/Greece (EN)") creates nothing: no outlet, no contact.
        val market = PrComplianceRules.normaliseMarket(col("market")) ?: return RowOutcome.Skipped("MARKET_UNKNOWN: ${col("market")}")
        val name = col("outlet").take(NAME).ifEmpty { return RowOutcome.Skipped("OUTLET_MISSING") }
        val tier = col("tier").toIntOrNull()?.takeIf { it in 1..3 }?.toShort() ?: return RowOutcome.Skipped("TIER_INVALID: ${col("tier")}")
        val outlet = upsertOutlet(name, market, tier, col("desk_contact").clean(DESK_CONTACT))
        // A desk-only row keeps its outlet (and desk address); only the contact is skipped.
        if (col("email").isEmpty()) return RowOutcome.Skipped("NO_EMAIL: ${outlet.name} (outlet saved)")
        val email = PrComplianceRules.normaliseEmail(col("email"))
        if (!EMAIL.matches(email) || email.length > EMAIL_MAX) return RowOutcome.Skipped("EMAIL_INVALID: ${col("email")}")
        val foundOn =
            col("found_on").ifEmpty { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return RowOutcome.Skipped("FOUND_ON_INVALID: $it") }
        val title = col("recent_article_title").ifEmpty { null }
        val notes = listOfNotNull(title?.let { "Recent article: $it" }, col("notes").ifEmpty { null }).joinToString("\n").ifEmpty { null }

        val existing = contacts.findFirstByEmailIgnoreCaseAndOutletId(email, outlet.id)
        val contact = existing ?: PrContact().apply { outletId = outlet.id }
        // Only values the file has overwrite; the status of an existing contact is never touched.
        val changed =
            listOf(
                merge(contact::email, email),
                merge(contact::market, market),
                merge(contact::name, col("person").clean(NAME)),
                merge(contact::role, col("role").clean(NAME)),
                merge(contact::emailSourceUrl, col("email_source_url").clean(URL)),
                merge(contact::emailFoundOn, foundOn),
                merge(contact::recentArticleUrl, col("recent_article_url").clean(URL)),
                merge(contact::notes, notes),
            ).any { it }
        if (existing != null) return if (changed) RowOutcome.Updated else RowOutcome.Unchanged
        if (suppressions.existsByEmailIgnoreCase(email)) contact.status = PrContactStatus.OPTED_OUT
        contacts.save(contact)
        return RowOutcome.Created
    }

    private fun upsertOutlet(
        name: String,
        market: String,
        tier: Short,
        deskContact: String?,
    ): PrOutlet {
        val outlet =
            outlets.findFirstByNameIgnoreCaseAndMarket(name, market) ?: PrOutlet().apply {
                this.name = name
                this.market = market
            }
        merge(outlet::tier, tier)
        merge(outlet::deskContact, deskContact)
        return outlets.save(outlet)
    }

    /** Sets [value] when it is non-null and differs; true when it changed something. */
    private fun <T> merge(
        property: KMutableProperty0<T>,
        value: T?,
    ): Boolean {
        if (value == null || property.get() == value) return false
        property.set(value)
        return true
    }

    // ---- Retention: contacts without activity for 24 months; their addresses stay on email_suppression ----

    /** Deletes contacts whose coalesce(last_contacted_at, created_at) is more than 24 months before [now]; their pitches cascade. */
    fun purgeInactive(now: Instant): Int {
        val cutoff = now.atZone(ZoneOffset.UTC).minusMonths(RETENTION_MONTHS).toInstant()
        val deleted = contacts.deleteInactiveSince(cutoff)
        log.info("PR retention: deleted {} contact(s) inactive since before {}", deleted, cutoff)
        return deleted
    }

    @Scheduled(cron = "\${pr.retention.cron:0 30 3 1 * *}", zone = "Europe/Zagreb")
    @SchedulerLock(name = "pr_retention", lockAtMostFor = "PT30M")
    fun purgeInactiveMonthly() {
        runCatching { purgeInactive(Instant.now()) }.onFailure { log.error("PR retention failed", it) }
    }

    // ---- helpers ----

    private fun outlet(id: Long): PrOutlet = outlets.findById(id).orElseThrow { PrException.notFound("OUTLET_NOT_FOUND", "Outlet $id not found") }

    private fun contact(id: Long): PrContact = contacts.findById(id).orElseThrow { PrException.notFound("CONTACT_NOT_FOUND", "Contact $id not found") }

    private fun outletName(id: Long?): String? = id?.let { outlets.findById(it).orElse(null)?.name }

    private fun requiredOutletName(raw: String?): String = raw?.trim()?.take(NAME)?.ifEmpty { null } ?: throw PrException.badRequest("NAME_REQUIRED", "Outlet name is required")

    /** Blank = no market; anything that is not a known country = 400 MARKET_UNKNOWN. */
    private fun market(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return PrComplianceRules.normaliseMarket(raw)
            ?: throw PrException.badRequest("MARKET_UNKNOWN", "Unknown market '$raw' (use an ISO country code such as GB, US, AU)")
    }

    private fun tier(value: Int): Short = value.toShort().takeIf { value in 1..3 } ?: throw PrException.badRequest("TIER_INVALID", "Tier must be 1, 2 or 3")

    private fun email(raw: String): String {
        val email = PrComplianceRules.normaliseEmail(raw)
        if (email.isEmpty()) throw PrException.badRequest("EMAIL_REQUIRED", "Email is required")
        if (!EMAIL.matches(email) || email.length > EMAIL_MAX) throw PrException.badRequest("EMAIL_INVALID", "'$raw' is not an email address")
        return email
    }

    private fun settable(status: PrContactStatus): PrContactStatus {
        if (status == PrContactStatus.OPTED_OUT) {
            throw PrException.badRequest("STATUS_NOT_ALLOWED", "OPTED_OUT is set only by the opt-out and bounce actions")
        }
        return status
    }

    private fun outletExists(
        name: String,
        market: String,
    ) = PrException.conflict("OUTLET_EXISTS", "Outlet '$name' already exists in $market")

    private fun contactExists(email: String) = PrException.conflict("CONTACT_EXISTS", "$email is already a contact at this outlet")

    private fun String?.clean(max: Int = Int.MAX_VALUE): String? = this?.trim()?.take(max)?.ifEmpty { null }

    private fun PrOutlet.toDto() = PrOutletDto(id!!, name, market!!, tier!!.toInt(), sectionUrl, deskContact, notes, createdAt)

    private fun PrContact.toDto(outletName: String?) =
        PrContactDto(
            id = id!!,
            outletId = outletId,
            outletName = outletName,
            name = name,
            role = role,
            email = email,
            market = market,
            emailSourceUrl = emailSourceUrl,
            emailFoundOn = emailFoundOn,
            status = status,
            recentArticleUrl = recentArticleUrl,
            notes = notes,
            lastContactedAt = lastContactedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    companion object {
        const val RETENTION_MONTHS = 24L
        private const val MAX_PAGE_SIZE = 500
        private const val NAME = 200
        private const val URL = 1000
        private const val URL_SHORT = 500
        private const val DESK_CONTACT = 320
        private const val EMAIL_MAX = 320
        private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        private val REQUIRED_COLUMNS = listOf("market", "outlet", "tier", "email")
    }
}
