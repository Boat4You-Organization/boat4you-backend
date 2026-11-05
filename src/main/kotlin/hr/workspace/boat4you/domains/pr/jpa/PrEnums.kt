package hr.workspace.boat4you.domains.pr.jpa

/** OPTED_OUT / DO_NOT_CONTACT never receive mail; CONSENT_REQUESTED / CONSENTED drive the DE and ZA rules. */
enum class PrContactStatus { ACTIVE, OPTED_OUT, DO_NOT_CONTACT, CONSENT_REQUESTED, CONSENTED }

enum class PrStudyStatus { DRAFT, LIVE, ARCHIVED }

enum class PrPitchTemplate { DATA_PITCH, FOLLOW_UP, BLOG_ARTICLE, NEWSJACK, CONSENT_REQUEST }

/** DRAFT -> SENDING is one atomic UPDATE (PrPitchRepository.markSending), so a double click sends once. */
enum class PrPitchStatus { DRAFT, SENDING, SENT, REPLIED, DECLINED, PUBLISHED, BOUNCED, FAILED }

/** BLOCKED_UNKNOWN = the page refused our bot (403/402/429): not proof the link is gone, so no alert. */
enum class PrLinkState { OK_FOLLOW, OK_NOFOLLOW, LINK_MISSING, PAGE_GONE, BLOCKED_UNKNOWN, NOT_CHECKED }

enum class PrMentionStatus { NEW, ASK_FOR_LINK, ASKED, LINKED, IGNORED }
