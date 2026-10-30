package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.CallSite
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.Year
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * E-mails to the admins about calls that need Mario (design §2.5): ready to book or transferred, callback wanted, do not call,
 * recording refused, technical failure, limit reached, failed quality criterion. Each carries the summary and a link to the
 * call in the admin, never the transcript. Sent through [EmailService], which defers to the caller's commit.
 */
@Component
class CallNotifier(
    private val emailService: EmailService,
    private val users: UserRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The mail transport; replaced in tests. */
    internal var send: (List<String>, String, String, Map<String, Any?>) -> Unit =
        { to, subject, template, vars -> emailService.sendEmail(to, subject, template, vars) }

    private val limitMailedDays = ConcurrentHashMap.newKeySet<LocalDate>()

    fun outcome(
        call: InquiryCall,
        inquiry: Inquiry,
    ) = mail(call, inquiry, OUTCOME_LABEL[call.outcome] ?: "poziv završen", call.summary)

    fun technicalFailure(
        call: InquiryCall,
        inquiry: Inquiry,
    ) = mail(call, inquiry, "tehnička greška", "Poziv nije uspio ni u drugom pokušaju: ${call.error ?: "nepoznato"}")

    fun quality(
        call: InquiryCall,
        inquiry: Inquiry,
    ) = mail(call, inquiry, "provjeriti kvalitetu", "Nika nije prošla kriterij kvalitete. ${call.summary ?: ""}".trim())

    /** At most one mail per Zagreb day per node. */
    fun limitReached(day: LocalDate) {
        if (!limitMailedDays.add(day)) return
        deliver(
            "Nika: dosegnut limit poziva ($day)",
            mapOf(
                "headline" to "Dosegnut je dnevni ili mjesečni limit poziva",
                "client" to "",
                "site" to "",
                "outcome" to "pozivi pauzirani",
                "summary" to "Novi pozivi čekaju dok limit ne istekne ili dok ga ne povećaš u postavkama Call centra.",
                "callbackTime" to "",
                "adminUrl" to "$ADMIN_URL/settings",
            ),
        )
    }

    private fun mail(
        call: InquiryCall,
        inquiry: Inquiry,
        label: String,
        summary: String?,
    ) {
        val client = listOfNotNull(inquiry.name, inquiry.surname).joinToString(" ").ifBlank { inquiry.phoneE164 ?: "klijent" }
        deliver(
            "Nika: $label – $client",
            mapOf(
                "headline" to "Nika: $label",
                "client" to client,
                "site" to (CallSite.fromKey(inquiry.site) ?: CallSite.BOAT4YOU).spokenName,
                "outcome" to label,
                "summary" to (summary ?: ""),
                "callbackTime" to (call.data?.get("callback_time")?.toString() ?: ""),
                "adminUrl" to "$ADMIN_URL/calls/${call.id}",
            ),
        )
    }

    private fun deliver(
        subject: String,
        vars: Map<String, Any?>,
    ) {
        val to = users.findAllAdminEmailAddresses()
        if (to.isEmpty()) {
            log.warn("Call notice not mailed: no admin e-mail address ({})", subject)
            return
        }
        send(to, subject, TEMPLATE, vars + ("currentYear" to Year.now(ZoneOffset.UTC).value))
    }

    companion object {
        const val TEMPLATE = "email/callsNotification"
        private const val ADMIN_URL = "https://admin.boat4you.com/call-center"
        private val OUTCOME_LABEL =
            mapOf(
                CallOutcome.READY_TO_BOOK to "spreman rezervirati",
                CallOutcome.TRANSFERRED to "prebačen tebi",
                CallOutcome.CALL_BACK to "traži povratni poziv",
                CallOutcome.DO_NOT_CALL to "ne zvati",
                CallOutcome.NO_CONSENT_TO_RECORD to "ne želi snimanje, nazovi osobno",
            )
    }
}
