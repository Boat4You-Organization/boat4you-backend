package hr.workspace.boat4you.domains.calls.offermail

import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
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
            // One deadline for all mailboxes together: the admin request never waits longer than this.
            val tasks = boxes.map { box -> Callable { search(box, client.trim().lowercase(), since) } }
            val futures = boxes.zip(pool.invokeAll(tasks, TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val failed = mutableListOf<String>()
            val found =
                futures.mapNotNull { (box, future) ->
                    try {
                        future.get()
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        log.warn("Offer mail: mailbox {} not read ({})", box.address, (e.cause ?: e).javaClass.simpleName)
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
            val folders = sentFolders(store)
            if (folders.isEmpty()) log.warn("Offer mail: no Sent folder in {}", box.address)
            return folders.mapNotNull { newestIn(it, box, client, since) }.maxByOrNull { it.sentAt }
        } finally {
            store.close()
        }
    }

    private fun newestIn(
        folder: Folder,
        box: OfferMailboxes.Mailbox,
        client: String,
        since: Instant,
    ): Sent? {
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
            collect(message, parts, attachments, related = false)
            return Sent(box.address, message.subject, message.sentDate.toInstant(), parts.joinToString("\n\n"), attachments.distinct())
        } finally {
            folder.close(false)
        }
    }

    /** Every folder flagged \Sent (RFC 6154) or with a usual Sent name (cPanel Dovecot: INBOX.Sent; Apple: Sent Messages). */
    private fun sentFolders(store: Store): List<Folder> =
        store.defaultFolder.list("*").filter { f ->
            (f as? IMAPFolder)?.attributes?.any { it.equals("\\Sent", ignoreCase = true) } == true ||
                SENT_NAMES.any { it.equals(f.fullName, ignoreCase = true) }
        }

    private fun addressedTo(
        m: Message,
        client: String,
    ): Boolean =
        listOf(Message.RecipientType.TO, Message.RecipientType.CC).any { type ->
            m.getRecipients(type).orEmpty().any { (it as? InternetAddress)?.address?.trim()?.lowercase() == client }
        }

    /**
     * Text parts in order; in an alternative the plain part wins, and attachments anywhere (also inside the HTML branch) are
     * listed by file name. Images inside a multipart/related (signature logos) are not attachments.
     */
    private fun collect(
        part: Part,
        texts: MutableList<String>,
        attachments: MutableList<String>,
        related: Boolean,
    ) {
        val named = part.fileName != null && !part.isMimeType("text/*")
        val attachment = Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || named
        when {
            attachment && related && part.isMimeType("image/*") && !Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) -> Unit
            attachment -> part.fileName?.let { attachments += runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
            part.isMimeType("text/plain") -> texts += OfferMailText.plain(part.content as String, html = false)
            part.isMimeType("text/html") -> texts += OfferMailText.plain(part.content as String, html = true)
            part.isMimeType("multipart/alternative") -> {
                val mp = part.content as Multipart
                val children = (0 until mp.count).map { mp.getBodyPart(it) }
                val chosen = children.firstOrNull { it.isMimeType("text/plain") } ?: children.firstOrNull { it.isMimeType("text/html") } ?: children.lastOrNull()
                chosen?.let { collect(it, texts, attachments, related) }
                // Apple Mail may put a PDF inside the HTML branch: list it even when the plain part was read.
                children.filter { it !== chosen }.forEach { collect(it, mutableListOf(), attachments, related) }
            }
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                val inRelated = related || part.isMimeType("multipart/related")
                for (i in 0 until mp.count) collect(mp.getBodyPart(i), texts, attachments, inRelated)
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
