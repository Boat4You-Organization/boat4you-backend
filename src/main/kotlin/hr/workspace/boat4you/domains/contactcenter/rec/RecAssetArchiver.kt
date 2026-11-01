package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * The CSS and fonts a recording needs to replay as it looked on its day (P6 R1.5). All seven sites are Next.js, whose stylesheets
 * carry their content hash in the name (`/_next/static/css/<hash>.css`), so the address is the version: each one a recording uses
 * is fetched once from the recording's own site, stored by content under `assets/<host>/<sha256>.<ext>` with the `/_next/static/`
 * fonts and images its url() loads, and those url() references are rewritten to `/replay-assets/<host>/<sha256>.<ext>`.
 * Only paths under `/_next/static/` of the recording's own site are ever fetched (no other host, no redirects off it).
 */
@Component
class RecAssetArchiver(
    private val jdbc: JdbcTemplate,
    @Value("\${cc.rec.worker-root-dir:/mnt/data/rec}") root: String,
    /** Tests only: one base URL for every site instead of https://www.<site host>. */
    @Value("\${cc.rec.asset-base-override:}") private val baseOverride: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val assets: Path = Path.of(root).resolve("assets")
    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build()

    /** Archives the stylesheets [paths] of [siteId] not archived yet and marks the known ones as used; returns the files stored. */
    fun archive(
        siteId: String,
        paths: Set<String>,
    ): Int {
        val base = baseOf(siteId) ?: return 0
        val hostDir = "${base.host}${if (base.port > 0) "_${base.port}" else ""}"
        var stored = 0
        for (path in paths.filter { CSS_PATH.matches(it) }) {
            val url = base.resolve(path).toString()
            if (touch(url)) continue
            val css = fetch(url) ?: continue
            val text =
                URL_REF.replace(String(css, Charsets.UTF_8)) { m ->
                    val ref = m.groupValues[2]
                    if (!ref.startsWith(NEXT_STATIC)) return@replace m.value
                    val asset = binary(base, hostDir, ref) ?: return@replace m.value
                    stored += asset.second
                    "url(/replay-assets/${asset.first})"
                }
            store(hostDir, url, text.toByteArray(Charsets.UTF_8), "css", "text/css")
            stored++
        }
        return stored
    }

    /** Deletes the assets no recording has used for [days] days, their files and their rows; returns how many. */
    fun purgeUnused(days: Long): Int {
        val old =
            jdbc.query(
                "SELECT id, path FROM rec_asset WHERE last_used_at < now() - make_interval(days => ?)",
                { rs, _ -> rs.getLong("id") to rs.getString("path") },
                days.toInt(),
            )
        old.forEach { (id, path) ->
            val file = assets.resolve(path).normalize()
            if (file.startsWith(assets)) Files.deleteIfExists(file)
            jdbc.update("DELETE FROM rec_asset WHERE id = ?", id)
        }
        return old.size
    }

    private fun baseOf(siteId: String): URI? =
        if (baseOverride.isNotBlank()) {
            URI(baseOverride)
        } else {
            SiteIds.HOSTS.entries.firstOrNull { it.value == siteId }?.let { URI("https://www.${it.key}") }
        }

    /** True, and marked as used now, when [url] is archived already. */
    private fun touch(url: String): Boolean = jdbc.update("UPDATE rec_asset SET last_used_at = now() WHERE url = ?", url) > 0

    /** The archived path of a font or image under /_next/static/, and 1 when it was stored now (0 when known). */
    private fun binary(
        base: URI,
        hostDir: String,
        ref: String,
    ): Pair<String, Int>? {
        val url = base.resolve(ref).toString()
        jdbc.query("SELECT path FROM rec_asset WHERE url = ? ORDER BY id DESC LIMIT 1", { rs, _ -> rs.getString(1) }, url).firstOrNull()?.let {
            touch(url)
            return it to 0
        }
        val bytes = fetch(url) ?: return null
        val ext = ref.substringAfterLast('.', "bin").takeIf { EXT.matches(it) } ?: "bin"
        return store(hostDir, url, bytes, ext, null) to 1
    }

    private fun store(
        hostDir: String,
        url: String,
        bytes: ByteArray,
        ext: String,
        contentType: String?,
    ): String {
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val path = "$hostDir/$sha.$ext"
        val file = assets.resolve(path)
        Files.createDirectories(file.parent)
        if (!Files.exists(file)) Files.write(file, bytes)
        jdbc.update(
            "INSERT INTO rec_asset (host, url, sha256, path, content_type, bytes) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            hostDir,
            url.take(URL_MAX),
            sha,
            path,
            contentType,
            bytes.size,
        )
        return path
    }

    @Suppress("TooGenericExceptionCaught") // a site that does not answer must never stop the worker
    private fun fetch(url: String): ByteArray? =
        try {
            val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            if (res.statusCode() == HTTP_OK && res.body().size <= MAX_ASSET_BYTES) res.body() else null
        } catch (e: Exception) {
            log.warn("rec asset not fetched {}: {}", url, e.toString())
            null
        }

    companion object {
        private const val NEXT_STATIC = "/_next/static/"
        private val CSS_PATH = Regex("""^/_next/static/css/[A-Za-z0-9._-]{1,120}\.css$""")
        private val CSS_IN_TEXT = Regex("""(?:https?://([A-Za-z0-9.-]+))?(/_next/static/css/[A-Za-z0-9._-]{1,120}\.css)""")
        private val URL_REF = Regex("""url\(\s*(['"]?)([^'")\s]+)\1\s*\)""")
        private val EXT = Regex("^[A-Za-z0-9]{1,8}$")
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val MAX_ASSET_BYTES = 5_000_000
        private const val HTTP_OK = 200
        private const val URL_MAX = 1000

        /** The `/_next/static/css/` stylesheets a recording's text names, relative or on [host] itself; another host is ignored. */
        fun stylesheets(
            text: String,
            host: String,
        ): Set<String> =
            CSS_IN_TEXT
                .findAll(text)
                .filter { m -> m.groupValues[1].isEmpty() || m.groupValues[1].equals(host, ignoreCase = true) }
                .map { it.groupValues[2] }
                .toSet()
    }
}
