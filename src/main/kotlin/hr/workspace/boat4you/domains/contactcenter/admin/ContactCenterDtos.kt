package hr.workspace.boat4you.domains.contactcenter.admin

import java.time.Instant

/** Filters of the conversation list (P2); every null or false field is left out of the query. */
data class ConversationFilter(
    val siteId: String? = null,
    val status: String? = null,
    val needsHuman: Boolean = false,
    val flaggedOnly: Boolean = false,
    val contactStatus: String? = null,
    val tag: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val q: String? = null,
    val includeSpam: Boolean = true,
    val before: Instant? = null,
    val beforeId: Long? = null,
    val size: Int = 50,
)

/** One conversation in the list; [lastMessage] is the newest message cut to 140 characters. */
data class ConversationRow(
    val id: Long,
    val siteId: String,
    val status: String,
    val locale: String,
    val visitorName: String?,
    val visitorEmail: String?,
    val phone: String?,
    val adminUnread: Boolean,
    val lastActivityAt: Instant,
    val lastMessage: String?,
    val lastMessageRole: String?,
    val lastSeenAt: Instant?,
    val currentPage: String?,
    val countryCode: String?,
    val country: String?,
    val ip: String?,
    val flagged: Boolean,
    val lockReason: String?,
    val lockedAt: Instant?,
    val missed: Boolean,
    val contactId: Long?,
    val contactStatus: String?,
    val tags: List<String>,
    val visitorId: Long?,
)
