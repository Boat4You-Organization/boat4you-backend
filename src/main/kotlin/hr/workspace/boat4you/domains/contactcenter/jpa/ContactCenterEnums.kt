package hr.workspace.boat4you.domains.contactcenter.jpa

/** Where a contact stands in the broker's pipeline (`cc_contact.status`, VARCHAR(20)). */
enum class ContactStatus { NEW, REPLIED, OFFER_SENT, NEGOTIATING, WON, LOST, SPAM }

/** How a visit reached the site (`cc_visit.channel`, VARCHAR(20)); SISTER = another of our own sites. */
enum class VisitChannel { ORGANIC, ADS, SISTER, DIRECT, REFERRAL, SOCIAL, EMAIL, OTHER }

/** `cc_visit.device_class`, VARCHAR(10). */
enum class DeviceClass { DESKTOP, TABLET, MOBILE }

/** What kind of page a page view was (`cc_page_view.page_type`, VARCHAR(20)). */
enum class PageType { HOME, BOAT, SEARCH, DESTINATION, BLOG, ITINERARY, BOOKING, OTHER }

/** `cc_event.type`, VARCHAR(30). */
enum class CcEventType { CLICK, SEARCH, CHAT_OPEN, GREETING_SHOWN, QUICK_REPLY, NUDGE_SHOWN, NUDGE_CLICK, NUDGE_CLOSE, CONSENT }

/** Which surface a search came from (`cc_search.source`, VARCHAR(20)). */
enum class SearchSource { AI_CHAT, SITE_SEARCH, FORM }
