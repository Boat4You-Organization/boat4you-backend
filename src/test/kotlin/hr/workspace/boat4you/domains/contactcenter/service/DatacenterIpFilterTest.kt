package hr.workspace.boat4you.domains.contactcenter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The optional hosting-network flag: an IP whose ASN is a cloud/hosting provider (design 2.6), off without the mmdb. */
class DatacenterIpFilterTest {
    private val filter = DatacenterIpFilter("/nonexistent.mmdb")

    @Test
    fun `hosting ASNs are flagged, consumer and privacy-relay networks are not`() {
        filter.reason("203.0.113.7") shouldBe null // no mmdb: the filter is off, never a false positive
        filter.asnOf = { ip -> mapOf("3.5.140.2" to 16509L, "95.216.1.1" to 24940L, "93.136.1.1" to 5391L, "172.226.1.1" to 36183L)[ip] }
        filter.reason("3.5.140.2") shouldBe "dc_ip" // AWS
        filter.reason("95.216.1.1") shouldBe "dc_ip" // Hetzner
        filter.reason("93.136.1.1") shouldBe null // Hrvatski Telekom
        filter.reason("172.226.1.1") shouldBe null // Akamai: iCloud Private Relay egress carries real people
        filter.reason("evil.example.com") shouldBe null // never a DNS lookup
        filter.reason(null) shouldBe null
    }
}
