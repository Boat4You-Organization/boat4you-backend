package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import java.time.Duration
import java.time.Instant

/**
 * Mirror of a partner catalogue that answers with NO yachts for an agency (HTTP 200, empty list).
 *
 * One such answer is a glitch until proven otherwise: the take-back guards (MMK 2026-05-28, NauSys 11.7.2026) refused
 * to switch off an agency's whole fleet on it, and a single empty answer still switches off nothing. But a partner
 * also empties a fleet on purpose: MMK stopped listing Multihull Yachting (agency 1605, 17 boats, 52 free weeks) on
 * 22.10.2026 06:10 UTC - its company still answered availability, the yacht list stayed [] run after run - and we kept
 * selling the boats for three days. Mario 25.10.2026: "MMK je maknuo prikaz plovila ... tako treba biti i kod nas, mi
 * prikazujemo sta na MMK i nausys daje."
 *
 * Rule: the first empty answer is recorded on the agency ([Agency.partnerFleetEmptySince], V9_95); an answer still
 * empty after [GRACE] mirrors the partner - every yacht of the agency goes off, exactly like a yacht missing from a
 * non-empty list. A non-empty answer clears the mark. Nothing is lost: the sync's own update switches a yacht on again
 * (sysActive = true) the moment the partner lists it. [MAX_EMPTY_AGENCIES] is the partner-wide breaker: that many
 * agencies marked empty at once is the partner being down, not fleets gone, and no fleet is mirrored.
 */
object PartnerFleetMirror {
    /** Two nights: the MMK yacht sync runs 06:10 UTC daily (+ 3 backups), so the third morning's empty answer decides. */
    val GRACE: Duration = Duration.ofHours(44)

    /** More agencies than this marked empty at once = the partner is down (8 of ~1,450 active agencies on 25.10.2026). */
    const val MAX_EMPTY_AGENCIES = 50L

    enum class Verdict {
        /** The first empty answer: marked on the agency, nothing goes off. */
        FIRST_SIGHTING,

        /** Still empty, for less than [GRACE]. */
        WITHIN_GRACE,

        /** Empty beyond [GRACE], but so are more than [MAX_EMPTY_AGENCIES] agencies: nothing goes off. */
        PARTNER_OUTAGE,

        /** Empty beyond [GRACE]: every yacht of the agency goes off. */
        MIRROR,
    }

    /**
     * The verdict on an empty answer for [agency]; a first sighting is marked on the agency (the caller saves it).
     * [emptyAgencies] counts the agencies currently marked - asked only once the grace has passed.
     */
    fun judge(
        agency: Agency,
        now: Instant,
        emptyAgencies: () -> Long,
    ): Verdict {
        val since = agency.partnerFleetEmptySince
        if (since == null) {
            agency.partnerFleetEmptySince = now
            return Verdict.FIRST_SIGHTING
        }
        if (Duration.between(since, now) < GRACE) return Verdict.WITHIN_GRACE
        return if (emptyAgencies() > MAX_EMPTY_AGENCIES) Verdict.PARTNER_OUTAGE else Verdict.MIRROR
    }

    /** A non-empty answer clears the mark; true when there was one to clear (the caller saves the agency). */
    fun fleetListed(agency: Agency): Boolean {
        if (agency.partnerFleetEmptySince == null) return false
        agency.partnerFleetEmptySince = null
        return true
    }
}
