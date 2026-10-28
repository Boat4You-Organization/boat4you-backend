package hr.workspace.boat4you.domains.contactcenter.service

import com.maxmind.geoip2.DatabaseReader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.net.InetAddress

/**
 * Flags a visit whose IP belongs to a hosting or cloud network (design 2.6, "IP-ovi podatkovnih centara"), by the autonomous system
 * number in the DB-IP ASN Lite database (db-ip.com, CC BY 4.0, the same vendor and format family as the country file that
 * [hr.workspace.boat4you.domains.chat.service.GeoIpService] reads). The mmdb is optional: a missing or broken file logs one WARN and
 * turns the filter off, so a visitor is never flagged on a guess.
 */
@Component
class DatacenterIpFilter(
    @Value("\${CC_ASN_DB_PATH:/home/cusma2/boat4you/dbip-asn-lite.mmdb}")
    private val dbPath: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val reader: DatabaseReader? by lazy {
        runCatching {
            DatabaseReader.Builder(File(dbPath)).build()
        }.onFailure {
            log.warn("ASN database not available at $dbPath, so hosting-network visits are not flagged: ${it.message}")
        }.getOrNull()
    }

    /** IP literal to its AS number, null when unknown; a var so a test can stand in for the mmdb. */
    internal var asnOf: (String) -> Long? = { ip ->
        runCatching { reader?.asn(InetAddress.getByName(ip))?.autonomousSystemNumber }.getOrNull()
    }

    /** [REASON] when [ip] is an address on a hosting network, else null. Only an IP literal is looked up, never a host name. */
    fun reason(ip: String?): String? = ip?.takeIf(TrackerClientIp::isIpLiteral)?.let(asnOf)?.takeIf { it in HOSTING_ASNS }?.let { REASON }

    companion object {
        const val REASON = "dc_ip"

        /**
         * Cloud and hosting networks whose address space carries servers, not people. Deliberately not listed, to keep false positives
         * low (design 2.6): Google 15169 (Translate proxy and other user-facing traffic; Google's crawlers are caught by their UA),
         * Cloudflare 13335 (WARP consumer VPN), Akamai 20940 and 36183 and Fastly 54113 (iCloud Private Relay egress).
         */
        val HOSTING_ASNS: Set<Long> =
            setOf(
                16509L, // Amazon AWS
                14618L, // Amazon AWS
                396982L, // Google Cloud
                8075L, // Microsoft Azure
                24940L, // Hetzner
                16276L, // OVH
                14061L, // DigitalOcean
                63949L, // Akamai Connected Cloud (Linode)
                20473L, // Vultr
                51167L, // Contabo
                12876L, // Scaleway
                31898L, // Oracle Cloud
                45102L, // Alibaba Cloud
                132203L, // Tencent Cloud
                9009L, // M247
                212238L, // Datacamp
            )
    }
}
