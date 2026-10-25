package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.external.service.PartnerFleetMirror.Verdict
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The partner fleet mirror (Mario 25.10.2026): an agency whose partner catalogue answers with no yachts is marked on
 * the first answer, switched off once the answer has lasted two nights, and spared when the partner as a whole is down.
 */
class PartnerFleetMirrorTests {
    private val now = Instant.parse("2026-10-25T06:10:00Z")
    private val agency = Agency().apply { id = 1605L }

    @Test
    fun `the first empty answer is marked on the agency, and the breaker is not even asked`() {
        var asked = false

        PartnerFleetMirror.judge(agency, now) {
            asked = true
            0L
        } shouldBe Verdict.FIRST_SIGHTING

        agency.partnerFleetEmptySince shouldBe now
        asked shouldBe false
    }

    @Test
    fun `an answer empty for less than two nights decides nothing, the mark stays`() {
        val since = now.minus(Duration.ofHours(43))
        agency.partnerFleetEmptySince = since

        PartnerFleetMirror.judge(agency, now) { 8L } shouldBe Verdict.WITHIN_GRACE

        agency.partnerFleetEmptySince shouldBe since
    }

    @Test
    fun `an answer empty for two nights mirrors the partner`() {
        agency.partnerFleetEmptySince = Instant.parse("2026-10-22T06:10:00Z")

        PartnerFleetMirror.judge(agency, now) { 8L } shouldBe Verdict.MIRROR
    }

    @Test
    fun `more than fifty agencies empty at once is the partner's outage, not fleets gone`() {
        agency.partnerFleetEmptySince = now.minus(PartnerFleetMirror.GRACE)

        PartnerFleetMirror.judge(agency, now) { PartnerFleetMirror.MAX_EMPTY_AGENCIES + 1 } shouldBe Verdict.PARTNER_OUTAGE
        PartnerFleetMirror.judge(agency, now) { PartnerFleetMirror.MAX_EMPTY_AGENCIES } shouldBe Verdict.MIRROR
    }

    @Test
    fun `a listed fleet clears the mark - once`() {
        agency.partnerFleetEmptySince = now

        PartnerFleetMirror.fleetListed(agency) shouldBe true
        agency.partnerFleetEmptySince shouldBe null
        PartnerFleetMirror.fleetListed(agency) shouldBe false
    }
}
