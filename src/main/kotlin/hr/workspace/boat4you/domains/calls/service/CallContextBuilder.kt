package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.CallSite
import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import java.time.Instant

/**
 * The dynamic variables Nika gets when the call starts (design §6.5). The boat is described only by manufacturer, model,
 * name and base, so no agency or owner name can ever reach the call. Prices are euro numbers only.
 */
object CallContextBuilder {
    private const val MESSAGE_MAX = 500
    private val DETAIL_KEYS = listOf("people", "kids", "cabins", "budgetEur", "boatType", "region", "crew")

    fun variables(
        inquiry: Inquiry,
        call: InquiryCall,
        now: Instant,
        s: CallsSettings.Snapshot,
    ): Map<String, String> {
        val site = CallSite.fromKey(inquiry.site) ?: CallSite.BOAT4YOU
        val details = inquiry.details.orEmpty()
        return linkedMapOf(
            "site_name" to site.spokenName,
            "site_domain" to site.domain,
            "client_name" to listOfNotNull(inquiry.name, inquiry.surname).joinToString(" ").trim(),
            "inquiry_kind" to kind(call.kind),
            "inquiry_summary" to summary(inquiry, details),
            "week_price_eur" to (details["weekPriceEur"]?.toString()?.toBigDecimalOrNull()?.toBigInteger()?.toString() ?: ""),
            "offer_summary" to (inquiry.offerNote ?: ""),
            "transfer_allowed" to CallClock.transferAllowed(now, s).toString(),
            "transfer_hours_text" to CallClock.transferHoursText(s),
            "language" to (call.language ?: inquiry.locale ?: "en"),
            "client_timezone" to (inquiry.phoneE164?.let { CallClock.clientZone(it).id } ?: CallClock.ZAGREB.id),
        )
    }

    private fun kind(kind: CallKind?) =
        when (kind) {
            CallKind.FOLLOW_UP -> "follow_up"
            CallKind.MANUAL -> "manual"
            else -> "confirmation"
        }

    private fun summary(
        inquiry: Inquiry,
        details: Map<String, Any?>,
    ): String {
        val yacht = inquiry.yacht
        val boat = boatLabel(yacht)
        val parts = mutableListOf<String>()
        boat?.takeIf { it.isNotBlank() }?.let { parts += "boat: $it" }
        if (inquiry.dateFrom != null && inquiry.dateTo != null) parts += "dates: ${inquiry.dateFrom} to ${inquiry.dateTo}"
        yacht?.location?.name?.let { parts += "base: $it" }
        DETAIL_KEYS.forEach { k -> details[k]?.toString()?.takeIf { it.isNotBlank() }?.let { parts += "${label(k)}: $it" } }
        inquiry.message?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "message: ${it.take(MESSAGE_MAX)}" }
        return parts.joinToString("; ")
    }

    /** Manufacturer, model and name of the boat ("Lagoon 46 Anthea"); never the agency or owner. */
    fun boatLabel(yacht: Yacht?): String? =
        yacht?.let { y ->
            val manufacturer = y.model?.manufacturer?.name
            val model = y.model?.name
            val head =
                if (manufacturer != null && model != null && model.startsWith(manufacturer, ignoreCase = true)) {
                    model
                } else {
                    listOfNotNull(manufacturer, model).joinToString(" ")
                }
            listOf(head, y.name).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { null }
        }

    private fun label(key: String) =
        when (key) {
            "budgetEur" -> "budget EUR"
            "boatType" -> "boat type"
            else -> key
        }
}
