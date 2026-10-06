package hr.workspace.boat4you.domains.catalouge.capacity

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * THE gate for partner free text on the capacity surfaces (capacity contract v1, section 6; critique B-1 / B-2): every
 * capacity note, MMK engine label and unknown sail label passes [capacityNote] in the backend before it enters a public
 * DTO, so b4y, the six sisters, the admin offer e-mail and the AI chat all get the same, already-checked text. The
 * frontends must not run these notes through their safePartnerText (it drops "+2" and rewrites "+1 skipper").
 *
 * Names: operators.txt (shipped as `partner/operators.txt`, a copy of infra/deploy-scripts/operators.txt - keep the two
 * in sync) without its '!' allow-list, plus every agency name, re-read every hour because the MMK agency mirror
 * creates new agencies on its own (critique B-2). The partner's internal remark (yacht.internal_remark) never goes
 * through here: it is admin-only and never reaches a public DTO.
 */
@Component
class PartnerTextSanitizer(
    private val agencyRepository: AgencyRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val operators: Pair<List<String>, List<String>> =
        PartnerNameMatcher.parseOperators(
            ClassPathResource(OPERATORS_RESOURCE).inputStream.bufferedReader().use { it.readText() },
        )

    private class Snapshot(
        val matcher: PartnerNameMatcher,
        val expiresAtMillis: Long,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** Time source, replaceable in tests. */
    internal var nowMillis: () -> Long = System::currentTimeMillis

    /** The note / label as a public surface may show it (normalized, never rewritten), or null to hide it. */
    fun capacityNote(text: String?): String? = CapacityNoteRules.capacityNote(text) { matcher().matches(it) }

    private fun matcher(): PartnerNameMatcher {
        val now = nowMillis()
        snapshot?.takeIf { now < it.expiresAtMillis }?.let { return it.matcher }
        synchronized(this) {
            snapshot?.takeIf { now < it.expiresAtMillis }?.let { return it.matcher }
            val fresh =
                try {
                    Snapshot(PartnerNameMatcher.of(operators.first, operators.second, agencyRepository.findAllNames()), now + AGENCY_TTL_MILLIS)
                } catch (e: Exception) {
                    // Keep the last good list (or operators.txt alone) and try again soon: hiding by operators.txt still
                    // works, and a failed lookup must never fail the page.
                    log.warn("Capacity-note sanitizer: agency names unavailable (${e.message}); retrying in a minute")
                    Snapshot(snapshot?.matcher ?: PartnerNameMatcher.of(operators.first, operators.second, emptyList()), now + RETRY_MILLIS)
                }
            snapshot = fresh
            return fresh.matcher
        }
    }

    companion object {
        const val OPERATORS_RESOURCE = "partner/operators.txt"
        private const val AGENCY_TTL_MILLIS = 60 * 60 * 1000L
        private const val RETRY_MILLIS = 60 * 1000L
    }
}
