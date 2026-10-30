package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.jpa.ContactStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Write side of the admin contacts (P2): edit, tags and the manual merge. Every change is one transaction; invalid input
 * throws [IllegalArgumentException] (the controller answers 400). Merging is always the broker's click: nothing here merges
 * on its own, because no identity is verified yet (`email_verified` is never set).
 */
@Service
class ContactService(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    /**
     * Changes the given fields; a null field stays as it is, a blank one is cleared. A changed email is no longer verified.
     * False when the contact does not exist.
     */
    fun update(
        id: Long,
        name: String?,
        email: String?,
        phone: String?,
        status: String?,
        note: String?,
    ): Boolean {
        val n = field(name, NAME_MAX, "name")
        val e = field(email, EMAIL_MAX, "email")
        val p = field(phone, PHONE_MAX, "phone")
        val t = field(note, NOTE_MAX, "note")
        require(status == null || ContactStatus.entries.any { it.name == status }) { "unknown status" }
        return tx.execute {
            jdbc.update(
                "UPDATE cc_contact SET " +
                    "name = CASE WHEN ? THEN ? ELSE name END, " +
                    "email_verified = CASE WHEN ? AND email IS DISTINCT FROM ? THEN FALSE ELSE email_verified END, " +
                    "email = CASE WHEN ? THEN ? ELSE email END, " +
                    "phone = CASE WHEN ? THEN ? ELSE phone END, " +
                    "note = CASE WHEN ? THEN ? ELSE note END, " +
                    "status = COALESCE(?, status), updated_at = now() WHERE id = ?",
                n != null,
                n?.value,
                e != null,
                e?.value,
                e != null,
                e?.value,
                p != null,
                p?.value,
                t != null,
                t?.value,
                status,
                id,
            ) == 1
        }!!
    }

    /** Replaces the contact's tags (trimmed, lower-case, 1 to 40 characters, at most 20); `spam` marks the contact SPAM. Null when missing. */
    fun setTags(
        id: Long,
        tags: List<String>,
    ): List<String>? {
        val clean = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
        require(clean.all { it.length <= TAG_MAX }) { "tag too long" }
        require(clean.size <= MAX_TAGS) { "too many tags" }
        return tx.execute {
            if (!lock(id)) return@execute null
            jdbc.update("DELETE FROM cc_contact_tag WHERE contact_id = ?", id)
            jdbc.batchUpdate("INSERT INTO cc_contact_tag (contact_id, tag) VALUES (?, ?)", clean.map { arrayOf<Any>(id, it) })
            val spam = if (SPAM_TAG in clean) ", status = 'SPAM'" else ""
            jdbc.update("UPDATE cc_contact SET updated_at = now()$spam WHERE id = ?", id)
            clean
        }
    }

    /**
     * Moves the source's conversations, visitors and tags to the target, fills the target's empty fields from the source
     * (a NEW target takes the source's status, notes are joined) and deletes the source, in one transaction. False when the
     * two are the same contact or either is missing.
     */
    fun merge(
        targetId: Long,
        sourceId: Long,
    ): Boolean {
        if (targetId == sourceId) return false
        return tx.execute {
            // Both rows locked in id order, so two merges of the same pair in opposite directions cannot deadlock.
            val locked = jdbc.queryForList("SELECT id FROM cc_contact WHERE id IN (?, ?) ORDER BY id FOR UPDATE", Long::class.java, targetId, sourceId)
            if (locked.size != 2) return@execute false
            jdbc.update("UPDATE ai_chat_session SET contact_id = ? WHERE contact_id = ?", targetId, sourceId)
            jdbc.update("UPDATE cc_visitor SET contact_id = ? WHERE contact_id = ?", targetId, sourceId)
            jdbc.update(
                "INSERT INTO cc_contact_tag (contact_id, tag) SELECT ?, tag FROM cc_contact_tag WHERE contact_id = ? ON CONFLICT DO NOTHING",
                targetId,
                sourceId,
            )
            jdbc.update(
                "UPDATE cc_contact t SET name = COALESCE(t.name, s.name), " +
                    "email_verified = CASE WHEN t.email IS NULL THEN s.email_verified ELSE t.email_verified END, " +
                    "email = COALESCE(t.email, s.email), phone = COALESCE(t.phone, s.phone), " +
                    "status = CASE WHEN t.status = 'NEW' THEN s.status ELSE t.status END, " +
                    "note = CASE WHEN t.note IS NULL OR t.note = '' THEN s.note WHEN s.note IS NULL OR s.note = '' THEN t.note " +
                    "ELSE t.note || E'\\n\\n' || s.note END, updated_at = now() FROM cc_contact s WHERE t.id = ? AND s.id = ?",
                targetId,
                sourceId,
            )
            // A `spam` tag that came with the source marks the target SPAM, as setTags does (P2 review M-8).
            jdbc.update(
                "UPDATE cc_contact SET status = 'SPAM' WHERE id = ? AND EXISTS (SELECT 1 FROM cc_contact_tag WHERE contact_id = ? AND tag = ?)",
                targetId,
                targetId,
                SPAM_TAG,
            )
            jdbc.update("DELETE FROM cc_contact WHERE id = ?", sourceId)
            true
        }!!
    }

    private fun lock(id: Long): Boolean = jdbc.queryForList("SELECT id FROM cc_contact WHERE id = ? FOR UPDATE", Long::class.java, id).isNotEmpty()

    /** A field to write: [value] null clears it. */
    private data class Field(
        val value: String?,
    )

    /** Null when not given (unchanged); a blank value clears the field; longer than [max] is refused. */
    private fun field(
        raw: String?,
        max: Int,
        label: String,
    ): Field? {
        if (raw == null) return null
        val v = raw.trim()
        require(v.length <= max) { "$label too long" }
        return Field(v.ifEmpty { null })
    }

    companion object {
        const val SPAM_TAG = "spam"
        private const val NAME_MAX = 120
        private const val EMAIL_MAX = 254
        private const val PHONE_MAX = 40
        private const val NOTE_MAX = 5000
        private const val TAG_MAX = 40
        private const val MAX_TAGS = 20
    }
}
