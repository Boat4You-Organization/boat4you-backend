package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import java.net.URI

/**
 * Which of the seven sites a new chat session belongs to.
 *
 * The browser's `Origin` header decides: a page cannot forge it, so a visitor on one site cannot open a session as another.
 * Only a call without an `Origin` (a server, not a browser) may name its site in [resolve]'s `requested` argument, and only
 * by a known id. Every other case, an unknown or unparsable Origin included, is `boat4you`, the site of the widgets that
 * predate the per-site chat.
 */
object ChatSiteResolver {
    fun resolve(
        origin: String?,
        requested: String?,
    ): String {
        if (origin != null) {
            val host = runCatching { URI(origin).host }.getOrNull()
            return host?.let(SiteIds::fromHost) ?: SiteIds.BOAT4YOU
        }
        return requested?.takeIf(SiteIds::isKnown) ?: SiteIds.BOAT4YOU
    }
}
