package hr.workspace.boat4you.domains.calls.offermail

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * The site mailboxes Mario sends offers from (cPanel IMAP, Mario 14.11.2026: "6-7 email adresa s kojih šaljem"). Only on the
 * API node (cusma2), only in its env file, written by infra/deploy-scripts/offer_mail_env_set.sh so no password ever
 * appears on screen or in a repo:
 *
 *   CALLS_OFFER_MAILBOXES=address1:base64(password1),address2:base64(password2)
 *   CALLS_OFFER_MAIL_HOST=optional host for all of them (default: mail.<domain of each address>)
 *
 * Base64 because a password may hold ',' or ':'. Empty = the feature is off and the admin keeps the one-sentence note.
 */
@Component
class OfferMailboxes(
    @Value("\${calls.offer-mail.mailboxes:}") raw: String,
    @Value("\${calls.offer-mail.host:}") host: String,
) {
    data class Mailbox(
        val address: String,
        val password: String,
        val host: String,
    ) {
        override fun toString() = "Mailbox($address @ $host)"
    }

    val all: List<Mailbox> = parse(raw, host.trim())

    companion object {
        fun parse(
            raw: String,
            host: String,
        ): List<Mailbox> =
            raw
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { entry ->
                    val address = entry.substringBefore(':').trim().lowercase()
                    val encoded = entry.substringAfter(':', "").trim()
                    val domain = address.substringAfter('@', "")
                    if (domain.isEmpty() || encoded.isEmpty()) return@mapNotNull null
                    val password = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
                    password?.takeIf { it.isNotEmpty() }?.let { Mailbox(address, it, host.ifEmpty { "mail.$domain" }) }
                }.distinctBy { it.address }
    }
}
