package hr.workspace.boat4you.domains.calls.offermail

import hr.workspace.boat4you.domains.calls.service.CallClock
import hr.workspace.boat4you.domains.catalouge.capacity.PartnerNameMatcher
import hr.workspace.boat4you.domains.catalouge.capacity.PartnerTextSanitizer
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * "Ponuda poslana" with the whole e-mail (Mario 14.11.2026): [preview] finds the offer Mario sent to the client and shows
 * him the text Nika will get; [sanitize] runs again on what he saves (he may have edited it), so no charter company name
 * reaches the call whatever the admin sends.
 */
@Service
class OfferMailService(
    private val reader: OfferMailReader,
    private val inquiries: InquiryRepository,
    private val sanitizer: PartnerTextSanitizer,
    private val manufacturers: ManufacturerRepository,
    private val models: ModelRepository,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Preview(
        /** False: no mailboxes on this server, the admin shows only the one-sentence note. */
        val configured: Boolean,
        val found: Boolean,
        val mailbox: String? = null,
        val subject: String? = null,
        val sentAt: Instant? = null,
        /** The text Nika gets: cleaned, partner names replaced, the attachment names on the last line. */
        val text: String? = null,
        /** Names that were replaced, as written in the mail, so Mario sees what was taken out. */
        val removed: List<String> = emptyList(),
        val searchedMailboxes: Int = 0,
        val failedMailboxes: List<String> = emptyList(),
    )

    data class Clean(
        val text: String?,
        val removed: List<String>,
    )

    fun preview(inquiryId: Long): Preview {
        val inquiry = inquiries.findById(inquiryId).orElseThrow { NoSuchElementException("inquiry $inquiryId") }
        if (!reader.configured) return Preview(configured = false, found = false)
        val client = inquiry.email?.trim()?.takeIf { it.contains('@') } ?: return Preview(configured = true, found = false)
        // A day before the inquiry: Mario may answer a phone or chat request with the offer before the form arrives.
        val since = inquiry.createdAt?.atZone(CallClock.ZAGREB)?.toInstant()?.minus(SINCE_MARGIN) ?: Instant.EPOCH
        val search = reader.latestTo(client, since)
        val sent = search.sent ?: return Preview(true, false, searchedMailboxes = search.searched, failedMailboxes = search.failed)
        val body = OfferMailText.clean(sent.body, html = false)
        val withFiles =
            if (sent.attachments.isEmpty()) body else "$body\n\nAttachments sent with the e-mail: ${sent.attachments.joinToString(", ")}"
        val clean = sanitize(withFiles)
        return Preview(
            configured = true,
            found = true,
            mailbox = sent.mailbox,
            subject = sent.subject?.let { sanitize(it).text },
            sentAt = sent.sentAt,
            text = clean.text,
            removed = clean.removed,
            searchedMailboxes = search.searched,
            failedMailboxes = search.failed,
        )
    }

    /** Partner names out, length capped; blank = null. Builders ("Lagoon", "Bali") stay. */
    fun sanitize(text: String?): Clean {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return Clean(null, emptyList())
        val capped = if (trimmed.length > STORED_MAX) trimmed.take(STORED_MAX) else trimmed
        val builders = folded { manufacturers.findAllNames() }
        val modelWords = folded { models.findAllNames() }.flatMap { it.split(' ') }.filter { it.length >= 2 }.toSet()
        val places = places()
        val (redacted, removed) = OfferMailText.redact(capped, sanitizer.partnerNames(capped), builders, modelWords, places)
        // Fail closed: a line that still names a company (a spelling the word split missed) goes as a whole.
        val (out, dropped) =
            OfferMailText.dropLeakingLines(redacted) { line ->
                sanitizer.partnerNames(line).any { it !in builders && it !in places }
            }
        if (dropped > 0) log.warn("Offer mail: {} line(s) still naming a charter company were replaced as a whole", dropped)
        return Clean(out, removed + List(dropped) { "(${OfferMailText.PARTNER})" })
    }

    /** Every marina, town, region and country name and each of their words, folded, plus the words an offer uses for a base. */
    private fun places(): Set<String> {
        val names =
            folded {
                jdbc.queryForList(
                    "SELECT name FROM location WHERE name IS NOT NULL UNION SELECT city FROM location WHERE city IS NOT NULL " +
                        "UNION SELECT name FROM country WHERE name IS NOT NULL UNION SELECT name FROM region WHERE name IS NOT NULL",
                    String::class.java,
                )
            }
        return names + names.flatMap { it.split(' ') }.filter { it.length >= 3 } + BASE_WORDS
    }

    /** Folded names from [read]; empty when the read fails, so every partner name is then replaced (never kept). */
    private fun folded(read: () -> List<String>): Set<String> =
        try {
            read().map { PartnerNameMatcher.fold(it) }.filter { it.isNotEmpty() }.toSet()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("Offer mail: builder / model names unavailable ({}), every partner name is replaced", e.message)
            emptySet()
        }

    companion object {
        /** The cleaned body ([OfferMailText.MAX]) plus the attachment line and Mario's edits. */
        const val STORED_MAX = 9000
        private val BASE_WORDS = setOf("marina", "aci", "port", "harbour", "harbor", "luka", "base", "porto", "puerto")
        private val SINCE_MARGIN = java.time.Duration.ofDays(1)
    }
}
