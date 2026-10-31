package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.chat.service.NameValidator
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Gives a conversation its contact (P2). The contact is the visitor's one when the visitor already has it, otherwise a new
 * one from the session's name, email and phone. The same email from another visitor is never merged on its own: merging is
 * the broker's call in the admin. Plain JDBC with conditional updates, never a whole-row save of the chat session, so a
 * concurrent chat write is not overwritten. One transaction per session; the session row is locked first, then the visitor.
 */
@Service
class ContactLinker(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val log = LoggerFactory.getLogger(javaClass)

    /** One pass of [linkPending]: sessions looked at, contacts given, and the last session id for the next pass. */
    data class LinkBatch(
        val scanned: Int,
        val linked: Int,
        val lastId: Long,
    )

    private data class SessionRow(
        val contactId: Long?,
        val visitorId: Long?,
        val name: String?,
        val email: String?,
        val phone: String?,
        val deleted: Boolean,
    )

    /**
     * Contact of this conversation: the existing one, the one of its visitor, or a new one from the session's name, email
     * and phone; null when the session has none of the three, is deleted, or does not exist.
     */
    fun ensureForSession(sessionId: Long): Long? {
        // The common case (the admin polls an open conversation every 4 s): already linked, read without a lock.
        val linked =
            jdbc
                .query(
                    "SELECT contact_id, visitor_id FROM ai_chat_session WHERE id = ? AND deleted_at IS NULL AND contact_id IS NOT NULL",
                    { rs, _ -> rs.getLong("contact_id") to rs.getLong("visitor_id").takeUnless { rs.wasNull() } },
                    sessionId,
                ).firstOrNull()
        if (linked != null) {
            linked.second?.let { claimVisitor(it, linked.first) }
            return linked.first
        }
        return tx.execute { link(sessionId) }
    }

    /**
     * After the chat stored a name, e-mail or phone: the linked contact gets the fields it does not have yet (a contact made
     * from the name alone never received the e-mail given later, Alison and Amanda 30.10.2026), an unlinked session its
     * contact as in [ensureForSession]. What the broker set is never replaced.
     */
    fun syncFromSession(sessionId: Long): Long? {
        val row =
            jdbc
                .query(
                    "SELECT contact_id, visitor_name, visitor_email, phone FROM ai_chat_session WHERE id = ? AND deleted_at IS NULL",
                    { rs, _ ->
                        SessionRow(
                            rs.getLong("contact_id").takeUnless { rs.wasNull() },
                            null,
                            rs.getString("visitor_name"),
                            rs.getString("visitor_email"),
                            rs.getString("phone"),
                            false,
                        )
                    },
                    sessionId,
                ).firstOrNull() ?: return null
        val contactId = row.contactId ?: return ensureForSession(sessionId)
        fillEmpty(contactId, NameValidator.validOrNull(row.name), clean(row.email, EMAIL_MAX), clean(row.phone, PHONE_MAX))
        return contactId
    }

    /** Links the next [batch] sessions after [afterId] that have a name, email or phone but no contact; keyset by id. */
    fun linkPending(
        afterId: Long,
        batch: Int = BATCH,
    ): LinkBatch {
        val ids =
            jdbc.queryForList(
                "SELECT id FROM ai_chat_session WHERE id > ? AND deleted_at IS NULL AND contact_id IS NULL " +
                    "AND (visitor_name IS NOT NULL OR visitor_email IS NOT NULL OR phone IS NOT NULL) ORDER BY id LIMIT ?",
                Long::class.java,
                afterId,
                batch,
            )
        // One session that fails (a deadlock with a merge, a lost connection) is logged and skipped, never the whole run.
        val linked =
            ids.count { id ->
                runCatching { ensureForSession(id) }
                    .onFailure { log.warn("CC contacts: session {} not linked: {}", id, it.toString()) }
                    .getOrNull() != null
            }
        return LinkBatch(ids.size, linked, ids.lastOrNull() ?: afterId)
    }

    private fun link(sessionId: Long): Long? {
        val row =
            jdbc
                .query(
                    "SELECT contact_id, visitor_id, visitor_name, visitor_email, phone, deleted_at IS NOT NULL AS deleted " +
                        "FROM ai_chat_session WHERE id = ? FOR UPDATE",
                    { rs, _ ->
                        SessionRow(
                            rs.getLong("contact_id").takeUnless { rs.wasNull() },
                            rs.getLong("visitor_id").takeUnless { rs.wasNull() },
                            rs.getString("visitor_name"),
                            rs.getString("visitor_email"),
                            rs.getString("phone"),
                            rs.getBoolean("deleted"),
                        )
                    },
                    sessionId,
                ).firstOrNull() ?: return null
        if (row.deleted) return null
        if (row.contactId != null) {
            row.visitorId?.let { claimVisitor(it, row.contactId) } // the visitor may have arrived after the contact
            return row.contactId
        }
        val name = NameValidator.validOrNull(row.name)
        val email = clean(row.email, EMAIL_MAX)
        val phone = clean(row.phone, PHONE_MAX)
        if (name == null && email == null && phone == null) return null
        val contactId = row.visitorId?.let(::visitorContact)?.also { fillEmpty(it, name, email, phone) } ?: insert(name, email, phone)
        jdbc.update("UPDATE ai_chat_session SET contact_id = ? WHERE id = ? AND contact_id IS NULL", contactId, sessionId)
        row.visitorId?.let { claimVisitor(it, contactId) }
        return contactId
    }

    private fun visitorContact(visitorId: Long): Long? =
        jdbc
            .query(
                "SELECT contact_id FROM cc_visitor WHERE id = ? FOR UPDATE",
                { rs, _ -> rs.getLong(1).takeUnless { rs.wasNull() } },
                visitorId,
            ).firstOrNull()

    private fun claimVisitor(
        visitorId: Long,
        contactId: Long,
    ) {
        jdbc.update("UPDATE cc_visitor SET contact_id = ? WHERE id = ? AND contact_id IS NULL", contactId, visitorId)
    }

    private fun insert(
        name: String?,
        email: String?,
        phone: String?,
    ): Long = jdbc.queryForObject("INSERT INTO cc_contact (name, email, phone) VALUES (?, ?, ?) RETURNING id", Long::class.java, name, email, phone)!!

    /** Fills only the contact's empty fields; a field the broker already set is never replaced. */
    private fun fillEmpty(
        contactId: Long,
        name: String?,
        email: String?,
        phone: String?,
    ) {
        jdbc.update(
            "UPDATE cc_contact SET name = COALESCE(name, ?), email = COALESCE(email, ?), phone = COALESCE(phone, ?), updated_at = now() " +
                "WHERE id = ? AND ((name IS NULL AND CAST(? AS VARCHAR) IS NOT NULL) OR (email IS NULL AND CAST(? AS VARCHAR) IS NOT NULL) " +
                "OR (phone IS NULL AND CAST(? AS VARCHAR) IS NOT NULL))",
            name,
            email,
            phone,
            contactId,
            name,
            email,
            phone,
        )
    }

    private fun clean(
        raw: String?,
        max: Int,
    ): String? = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }

    companion object {
        const val BATCH = 500
        private const val EMAIL_MAX = 254
        private const val PHONE_MAX = 40
    }
}
