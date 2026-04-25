package hr.workspace.boat4you.domains.branding

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Resolves the active brand for an inbound request.
 *
 * Order of precedence:
 *   1. Explicit `X-Boat4You-Brand: {id}` header. Front-ends should set
 *      this — it's the only signal that survives reverse-proxy
 *      rewrites and origin masking.
 *   2. `Origin` / `Referer` host. Heuristic fallback for clients that
 *      forgot the header (still better than always defaulting to
 *      Boat4You). Maps `catamaran-croatia-charter.com` → `catamaran-
 *      croatia`, etc.
 *   3. Registry default (Boat4You) — never crash, even on stray curl
 *      hits.
 *
 * Resolver never throws; the brand is always returned. Logging makes it
 * easy to spot fronts that aren't sending the header in prod.
 */
@Component
class BrandResolver(
    private val registry: BrandRegistry,
) {
    private val log = LoggerFactory.getLogger(BrandResolver::class.java)

    /** Map of known web hosts to brand ids. Same domain shape as the
     *  brand `websiteUrl` but stored as a normalized lowercase host so
     *  arbitrary protocol / port mismatches still resolve. */
    private val hostToBrandId: Map<String, String> by lazy {
        registry.byId.values.associate { brand ->
            URI(brand.websiteUrl).host.removePrefix("www.").lowercase() to brand.id
        }
    }

    fun resolve(request: HttpServletRequest): Brand {
        val headerId = request.getHeader(HEADER)?.trim()?.lowercase()
        if (!headerId.isNullOrBlank() && registry.byId.containsKey(headerId)) {
            return registry.get(headerId)
        }
        if (!headerId.isNullOrBlank()) {
            log.warn("Unknown brand id '{}' on header {} — falling back", headerId, HEADER)
        }
        // Heuristic origin fallback.
        val originHost = listOf("Origin", "Referer")
            .asSequence()
            .mapNotNull { request.getHeader(it) }
            .mapNotNull { runCatching { URI(it).host?.removePrefix("www.")?.lowercase() }.getOrNull() }
            .firstOrNull()
        if (originHost != null) {
            hostToBrandId[originHost]?.let { id -> return registry.get(id) }
        }
        return registry.default
    }

    /** Convenience overload for service-layer call sites that don't want
     *  to drag the request object around. Pass the header value
     *  directly. */
    fun resolveById(id: String?): Brand = registry.get(id)

    companion object {
        const val HEADER = "X-Boat4You-Brand"
    }
}
