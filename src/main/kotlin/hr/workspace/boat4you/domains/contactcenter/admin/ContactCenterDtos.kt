package hr.workspace.boat4you.domains.contactcenter.admin

import java.time.Instant
import java.time.LocalDate

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
    /** The client card's conversations; not a list filter of the admin screen. */
    val contactId: Long? = null,
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

/** One message of a conversation; [authorUserId] is the admin who wrote an ADMIN message. */
data class MessageView(
    val id: Long,
    val role: String,
    val content: String,
    val payload: String?,
    val createdAt: Instant,
    val authorUserId: Long?,
)

/** One page the visitor opened, on any of the sites. */
data class PageViewItem(
    val siteId: String,
    val path: String,
    val title: String?,
    val pageType: String?,
    val at: Instant,
)

/** One search: from the AI chat, the site's search form or an inquiry form. */
data class SearchItem(
    val source: String,
    val siteId: String,
    val location: String?,
    val countryCode: String?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val minPersons: Int?,
    val minCabins: Int?,
    val vesselType: String?,
    val resultCount: Int?,
    val at: Instant,
)

/** A conversation opened in the admin: messages oldest first, the visitor's navigation and searches, and the response stamps. */
data class ConversationDetail(
    val row: ConversationRow,
    val referrer: String?,
    val pageTrail: String?,
    val messages: List<MessageView>,
    val history: List<PageViewItem>,
    val searches: List<SearchItem>,
    val firstResponseAt: Instant?,
    val firstResponseBy: String?,
    val takenOverAt: Instant?,
    val handoffRequestedAt: Instant?,
    val aiReturnedAt: Instant?,
)

/** One contact in the contacts list; [conversations] and [lastActivityAt] count its non-deleted conversations. */
data class ContactRow(
    val id: Long,
    val name: String?,
    val email: String?,
    val phone: String?,
    val status: String,
    val tags: List<String>,
    val conversations: Int,
    val lastActivityAt: Instant?,
    val createdAt: Instant,
)

/** How one visit reached a site: its channel, referrer, campaign and, for a sister site, which one. */
data class SourceView(
    val channel: String,
    val siteId: String,
    val referrer: String?,
    val utmSource: String?,
    val utmMedium: String?,
    val utmCampaign: String?,
    val fromSiteId: String?,
    val landingUrl: String?,
    val at: Instant,
)

data class VisitItem(
    val id: Long,
    val siteId: String,
    val startedAt: Instant,
    val pageCount: Int,
    val channel: String,
    val countryCode: String?,
)

/** The client card: the contact, where they came from, their visits, conversations, searches and boats, and unverified look-alikes. */
data class ContactCard(
    val contact: ContactRow,
    val note: String?,
    val emailVerified: Boolean,
    val firstSource: SourceView?,
    val lastSource: SourceView?,
    val visits: List<VisitItem>,
    val conversations: List<ConversationRow>,
    val searches: List<SearchItem>,
    val boatsViewed: List<String>,
    val possibleDuplicates: List<ContactRow>,
)
