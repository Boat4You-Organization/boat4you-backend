package hr.workspace.boat4you.domains.calls.offermail

import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.AndTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SentDateTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the last e-mail Mario sent to a client in the Sent folders of the site mailboxes ([OfferMailboxes]). Read-only: the
 * folders are opened READ_ONLY and messages are peeked, so nothing in the mailboxes changes. One short connection per
 * mailbox, all in parallel, only when Mario opens "Ponuda poslana"; nothing keeps running afterwards.
 */
@Component
class OfferMailReader(
    private val mailboxes: OfferMailboxes,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** IMAP over TLS on 993 in production; tests point it at a local plain-IMAP server. */
    internal var protocol = "imaps"
    internal var port = PORT

    data class Sent(
        val mailbox: String,
        val subject: String?,
        val sentAt: Instant,
        /** Plain text of the parts Mario wrote, in order (HTML parts already converted). */
        val body: String,
        val attachments: List<String>,
    )

    data class Search(
        val sent: Sent?,
        /** Mailboxes that could not be read (address only, never the reason with a password in it). */
        val failed: List<String>,
        val searched: Int,
    )

    val configured: Boolean get() = mailboxes.all.isNotEmpty()

    /** The newest mail to [client] sent at or after [since], over all mailboxes; null when there is none. */
    fun latestTo(
        client: String,
        since: Instant,
    ): Search {
        val boxes = mailboxes.all
        if (boxes.isEmpty()) return Search(null, emptyList(), 0)
        val pool = Executors.newFixedThreadPool(boxes.size.coerceAtMost(MAX_PARALLEL)) { r -> Thread(r, "offer-mail").apply { isDaemon = true } }
        try {
            val futures = boxes.map { box -> box to pool.submit(Callable { search(box, client.trim().lowercase(), since) }) }
            val failed = mutableListOf<String>()
            val found =
                futures.mapNotNull { (box, future) ->
                    try {
                        future.get(TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        future.cancel(true)
                        log.warn("Offer mail: mailbox {} not read ({})", box.address, e.javaClass.simpleName)
                        failed += box.address
                        null
                    }
                }
            return Search(found.maxByOrNull { it.sentAt }, failed, boxes.size)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun search(
        box: OfferMailboxes.Mailbox,
        client: String,
        since: Instant,
    ): Sent? {
        val session = Session.getInstance(props(box.host))
        val store: Store = session.getStore(protocol)
        store.connect(box.host, port, box.address, box.password)
        try {
            val folder = sentFolder(store) ?: return null.also { log.warn("Offer mail: no Sent folder in {}", box.address) }
            folder.open(Folder.READ_ONLY)
            try {
                val term =
                    AndTerm(
                        OrTerm(RecipientStringTerm(Message.RecipientType.TO, client), RecipientStringTerm(Message.RecipientType.CC, client)),
                        SentDateTerm(SentDateTerm.GE, Date.from(since)),
                    )
                val message =
                    folder
                        .search(term)
                        .filter { m -> m.sentDate != null && !m.sentDate.toInstant().isBefore(since) && addressedTo(m, client) }
                        .maxByOrNull { it.sentDate } ?: return null
                (message as? IMAPMessage)?.peek = true
                val parts = mutableListOf<String>()
                val attachments = mutableListOf<String>()
                collect(message, parts, attachments)
                return Sent(box.address, message.subject, message.sentDate.toInstant(), parts.joinToString("\n\n"), attachments)
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }

    /** The folder flagged \Sent (RFC 6154), else the usual names (cPanel Dovecot: INBOX.Sent). */
    private fun sentFolder(store: Store): Folder? {
        val all = store.defaultFolder.list("*")
        all.firstOrNull { f -> (f as? IMAPFolder)?.attributes?.any { it.equals("\\Sent", ignoreCase = true) } == true }?.let { return it }
        return SENT_NAMES.firstNotNullOfOrNull { name -> all.firstOrNull { it.fullName.equals(name, ignoreCase = true) } }
    }

    private fun addressedTo(
        m: Message,
        client: String,
    ): Boolean =
        listOf(Message.RecipientType.TO, Message.RecipientType.CC).any { type ->
            m.getRecipients(type).orEmpty().any { (it as? InternetAddress)?.address?.trim()?.lowercase() == client }
        }

    /** Text parts in order; in an alternative the plain part wins. Attachments only by file name. */
    private fun collect(
        part: Part,
        texts: MutableList<String>,
        attachments: MutableList<String>,
    ) {
        val attachment = Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || (part.fileName != null && !part.isMimeType("text/*"))
        when {
            attachment -> part.fileName?.let { attachments += it }
            part.isMimeType("text/plain") -> texts += OfferMailText.plain(part.content as String, html = false)
            part.isMimeType("text/html") -> texts += OfferMailText.plain(part.content as String, html = true)
            part.isMimeType("multipart/alternative") -> {
                val mp = part.content as Multipart
                val children = (0 until mp.count).map { mp.getBodyPart(it) }
                val chosen = children.firstOrNull { it.isMimeType("text/plain") } ?: children.firstOrNull { it.isMimeType("text/html") } ?: children.lastOrNull()
                chosen?.let { collect(it, texts, attachments) }
            }
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                for (i in 0 until mp.count) collect(mp.getBodyPart(i), texts, attachments)
            }
        }
    }

    private fun props(host: String) =
        Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", host)
            put("mail.$protocol.port", port.toString())
            put("mail.$protocol.ssl.checkserveridentity", "true")
            put("mail.$protocol.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
            put("mail.$protocol.timeout", READ_TIMEOUT_MS.toString())
            put("mail.$protocol.writetimeout", READ_TIMEOUT_MS.toString())
        }

    private companion object {
        const val PORT = 993
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 15_000
        const val TOTAL_TIMEOUT_SECONDS = 25L
        const val MAX_PARALLEL = 8
        val SENT_NAMES = listOf("INBOX.Sent", "Sent", "Sent Messages", "INBOX.Sent Messages", "Sent Items", "INBOX.Sent Items")
    }
}
