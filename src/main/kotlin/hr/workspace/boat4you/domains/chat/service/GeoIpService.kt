package hr.workspace.boat4you.domains.chat.service

import com.maxmind.geoip2.DatabaseReader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.net.InetAddress

/**
 * IP -> country for the chat inbox, backed by the DB-IP Country Lite database
 * (db-ip.com, CC BY 4.0 — attribution kept here and in the admin UI). The mmdb
 * file lives next to the jar; a missing/broken file just means no geo — the
 * chat itself never depends on it.
 */
@Service
class GeoIpService(
    @Value("\${GEOIP_DB_PATH:/home/cusma2/boat4you/dbip-country-lite.mmdb}")
    private val dbPath: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val reader: DatabaseReader? by lazy {
        runCatching {
            DatabaseReader.Builder(File(dbPath)).build()
        }.onFailure {
            log.warn("GeoIP database not available at $dbPath — chat sessions get no country: ${it.message}")
        }.getOrNull()
    }

    data class GeoCountry(val code: String, val name: String)

    fun countryOf(ip: String?): GeoCountry? {
        if (ip.isNullOrBlank()) return null
        return runCatching {
            val response = reader?.country(InetAddress.getByName(ip)) ?: return null
            val code = response.country?.isoCode ?: return null
            GeoCountry(code, response.country?.name ?: code)
        }.getOrNull()
    }
}
