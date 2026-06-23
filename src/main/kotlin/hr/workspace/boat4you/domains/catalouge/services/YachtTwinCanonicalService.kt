package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.config.TwinCanonicalProperties
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTwinRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Resolves a requested yacht id to the canonical copy of its cross-source
 * twin group, so the detail page (and everything the frontend derives from the
 * returned slug — calendar, price-calc, reservation) operates on the copy with
 * the most complete bookable calendar / highest forward margin.
 *
 * Booking safety: by canonicalizing at the detail-resolution level the returned
 * `YachtDetailsDto` carries the canonical id+slug; the reservation flow's
 * `offer.yacht == yacht` guard therefore still holds (the page books against
 * the canonical yacht's own offers). NEVER merge raw offers across yacht ids —
 * that guard would reject the booking.
 */
@Service
class YachtTwinCanonicalService(
    private val yachtTwinRepository: YachtTwinRepository,
    private val props: TwinCanonicalProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @return the canonical yacht id for [id]'s twin group, or [id] unchanged
     * when canonicalization is off, [id] is outside the pilot allow-list, there
     * are no twins, or no twin has forward availability. Fails open — any error
     * returns [id] so a detail page never breaks.
     */
    fun resolve(id: Long?): Long? {
        if (id == null || !props.enabled) return id
        // Pilot gate: while an allow-list is set, only act for those ids — and
        // skip the twin query entirely for every other boat (zero overhead).
        if (props.pilotYachtIds.isNotEmpty() && id !in props.pilotYachtIds) return id

        return try {
            val group = yachtTwinRepository.findTwinIds(id)
            if (group.size <= 1) {
                id
            } else {
                val canonical = yachtTwinRepository.pickCanonicalYachtId(group, LocalDate.now())
                if (canonical != null && canonical != id) {
                    log.debug("twin-canonical: yacht {} -> {} (group {})", id, canonical, group)
                }
                canonical ?: id
            }
        } catch (e: Exception) {
            log.warn("twin-canonical resolve failed for yacht {}: {}", id, e.message)
            id
        }
    }
}
