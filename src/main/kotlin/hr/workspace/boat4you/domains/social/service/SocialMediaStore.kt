package hr.workspace.boat4you.domains.social.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.domains.social.jpa.SocialMedia
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialMediaOrigin
import hr.workspace.boat4you.domains.social.jpa.SocialMediaRepository
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Files of Objave under `social.media-dir`: `{token}/media.jpg` (every photo re-encoded as JPEG, longest side ≤ 2160 px:
 * Instagram takes JPEG only) or `{token}/media.mp4|mov` plus `{token}/thumb.jpg` (ffmpeg, when installed). Served
 * publicly at [publicUrl] so Instagram and Threads, which fetch by URL, can read them; the 30-character token makes
 * the URL unguessable. Type is decided by magic bytes, never by the client's Content-Type or name.
 */
@Component
class SocialMediaStore(
    private val props: SocialProperties,
    private val repo: SocialMediaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root: Path get() = Path.of(props.mediaDir)

    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    /** Store a stream (admin upload, automation upload, downloaded URL, generated video) and its row. */
    fun store(
        input: InputStream,
        originalName: String?,
        uploadedBy: Long?,
        origin: SocialMediaOrigin = SocialMediaOrigin.UPLOAD,
        originRef: String? = null,
    ): SocialMedia {
        Files.createDirectories(root)
        val tmp = Files.createTempFile(root, "upload-", ".part")
        try {
            val limit = props.maxVideoMb * MB
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newOutputStream(tmp).use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > limit) throw SocialValidationException("File larger than ${props.maxVideoMb} MB")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            if (size == 0L) throw SocialValidationException("Empty file")
            val head = Files.newInputStream(tmp).use { it.readNBytes(HEAD_BYTES) }
            val type = sniff(head) ?: throw SocialValidationException("Only JPEG, PNG, WebP, MP4 or MOV")
            val token = SocialCrypto.randomToken(TOKEN_LENGTH)
            val dir = Files.createDirectories(root.resolve(token))
            val media =
                SocialMedia().apply {
                    this.token = token
                    this.origin = origin
                    this.originRef = originRef
                    this.fileName = safeName(originalName, type)
                    this.uploadedBy = uploadedBy
                    this.sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                }
            if (type.kind == SocialMediaKind.IMAGE) {
                if (size > props.maxImageMb * MB) throw SocialValidationException("Photo larger than ${props.maxImageMb} MB")
                val jpeg = SocialImages.normalize(Files.readAllBytes(tmp)) ?: throw SocialValidationException("The photo cannot be read")
                Files.write(dir.resolve("media.jpg"), jpeg.bytes)
                media.kind = SocialMediaKind.IMAGE
                media.mimeType = "image/jpeg"
                media.storagePath = "$token/media.jpg"
                media.sizeBytes = jpeg.bytes.size.toLong()
                media.width = jpeg.width
                media.height = jpeg.height
            } else {
                val name = "media.${type.ext}"
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                media.kind = SocialMediaKind.VIDEO
                media.mimeType = type.mime
                media.storagePath = "$token/$name"
                media.sizeBytes = size
                probeVideo(dir.resolve(name))?.let { (w, h, d) ->
                    media.width = w
                    media.height = h
                    media.durationSec = d
                }
                if (thumbnail(dir.resolve(name), dir.resolve("thumb.jpg"))) media.thumbnailPath = "$token/thumb.jpg"
            }
            return repo.save(media)
        } finally {
            tmp.deleteIfExists()
        }
    }

    /**
     * Download a picture or video for the automation / the AI. Our own API (`publicApiUrl`, e.g. a catalogue photo at
     * /public/image/{id}) is always allowed; any other host must be https and resolve to public addresses only (SSRF guard).
     */
    fun storeFromUrl(
        url: String,
        uploadedBy: Long?,
        origin: SocialMediaOrigin = SocialMediaOrigin.URL,
        originRef: String? = null,
    ): SocialMedia {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: throw SocialValidationException("Bad media URL")
        val own = url.trim().startsWith(props.publicApiUrl.trimEnd('/') + "/") || url.trim().startsWith(props.selfBaseUrl.trimEnd('/') + "/")
        if (!own) assertPublicHttps(uri)
        val res =
            http.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_S)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        if (res.statusCode() !in 200..299) {
            res.body().close()
            throw SocialValidationException("Media URL answered ${res.statusCode()}")
        }
        return res.body().use { store(it, uri.path.substringAfterLast('/'), uploadedBy, origin, originRef ?: url.take(ORIGIN_REF_MAX)) }
    }

    fun file(media: SocialMedia): Path = root.resolve(media.storagePath).normalize().also { require(it.startsWith(root.normalize())) }

    fun thumbnailFile(media: SocialMedia): Path? = media.thumbnailPath?.let { root.resolve(it).normalize() }?.takeIf { it.startsWith(root.normalize()) }

    fun publicUrl(media: SocialMedia): String = "${props.publicApiUrl.trimEnd('/')}/public/social/media/${media.token}.${media.storagePath.substringAfterLast('.')}"

    fun thumbnailUrl(media: SocialMedia): String? = media.thumbnailPath?.let { "${props.publicApiUrl.trimEnd('/')}/public/social/media/${media.token}.thumb.jpg" }

    /** The photo as JPEG of at most [maxBytes] (Bluesky: 1 MB per image), shrinking quality, then size. */
    fun jpegAtMost(
        media: SocialMedia,
        maxBytes: Int,
    ): ByteArray {
        val bytes = Files.readAllBytes(file(media))
        if (bytes.size <= maxBytes) return bytes
        return SocialImages.shrink(bytes, maxBytes) ?: throw SocialValidationException("The photo cannot be made small enough")
    }

    private fun assertPublicHttps(uri: URI) {
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) throw SocialValidationException("Media URL must be https")
        val addresses = runCatching { InetAddress.getAllByName(uri.host).toList() }.getOrDefault(emptyList())
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw SocialValidationException("Media URL host is not public")
    }

    private fun isPublic(a: InetAddress): Boolean =
        !(a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) &&
            !(a is Inet4Address && (a.address[0].toInt() and 0xff) == CGNAT_FIRST && (a.address[1].toInt() and 0xc0) == CGNAT_SECOND_MASK) &&
            !(a.hostAddress.startsWith("fc") || a.hostAddress.startsWith("fd"))

    private fun probeVideo(path: Path): Triple<Int?, Int?, Int?>? =
        runCatching {
            val p =
                ProcessBuilder(
                    "ffprobe",
                    "-v",
                    "error",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "stream=width,height:format=duration",
                    "-of",
                    "json",
                    path.toString(),
                ).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            if (!p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            val json = jacksonObjectMapper().readTree(out)
            val stream = json.path("streams").path(0)
            Triple(
                stream.path("width").asInt(0).takeIf { it > 0 },
                stream.path("height").asInt(0).takeIf { it > 0 },
                json.path("format").path("duration").asText("").toDoubleOrNull()?.roundToInt(),
            )
        }.onFailure { log.info("ffprobe unavailable or failed: {}", it.message) }.getOrNull()

    private fun thumbnail(
        video: Path,
        out: Path,
    ): Boolean =
        runCatching {
            val p =
                ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-ss", "1", "-i", video.toString(), "-frames:v", "1", "-vf", "scale=720:-2", out.toString())
                    .redirectErrorStream(true)
                    .start()
            p.inputStream.readBytes()
            p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.exists(out)
        }.getOrDefault(false)

    data class Sniffed(
        val kind: SocialMediaKind,
        val mime: String,
        val ext: String,
    )

    companion object {
        private const val MB = 1024L * 1024L
        private const val BUFFER = 64 * 1024
        private const val HEAD_BYTES = 16
        private const val TOKEN_LENGTH = 30
        private const val CONNECT_TIMEOUT_S = 10L
        private const val DOWNLOAD_TIMEOUT_S = 120L
        private const val PROBE_TIMEOUT_S = 30L
        private const val ORIGIN_REF_MAX = 200
        private const val CGNAT_FIRST = 100
        private const val CGNAT_SECOND_MASK = 0x40
        private const val NAME_MAX = 120

        /** JPEG FF D8 FF, PNG 89 50 4E 47, WebP RIFF....WEBP, MP4/MOV ....ftyp (qt = MOV). */
        fun sniff(head: ByteArray): Sniffed? {
            fun at(
                i: Int,
                s: String,
            ) = head.size >= i + s.length && s.indices.all { head[i + it] == s[it].code.toByte() }
            return when {
                head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() ->
                    Sniffed(SocialMediaKind.IMAGE, "image/jpeg", "jpg")
                head.size >= 4 && head[0] == 0x89.toByte() && at(1, "PNG") -> Sniffed(SocialMediaKind.IMAGE, "image/png", "png")
                at(0, "RIFF") && at(8, "WEBP") -> Sniffed(SocialMediaKind.IMAGE, "image/webp", "webp")
                at(4, "ftypqt") -> Sniffed(SocialMediaKind.VIDEO, "video/quicktime", "mov")
                at(4, "ftyp") -> Sniffed(SocialMediaKind.VIDEO, "video/mp4", "mp4")
                else -> null
            }
        }

        fun safeName(
            original: String?,
            type: Sniffed,
        ): String {
            val base =
                original
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    ?.take(NAME_MAX)
                    ?.takeIf { it.isNotBlank() }
            return base ?: "media.${type.ext}"
        }
    }
}

/** OpenCV decode / resize / JPEG encode, releasing every native buffer. */
object SocialImages {
    init {
        nu.pattern.OpenCV.loadLocally()
    }

    data class Jpeg(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    private const val MAX_SIDE = 2160
    private const val QUALITY = 90
    private const val MIN_QUALITY = 50
    private const val QUALITY_STEP = 10
    private const val SHRINK_FACTOR = 0.8

    private inline fun <T : Mat, R> T.using(block: (T) -> R): R =
        try {
            block(this)
        } finally {
            runCatching { release() }
        }

    /** Any JPEG / PNG / WebP → JPEG, longest side ≤ 2160; null when undecodable. */
    fun normalize(bytes: ByteArray): Jpeg? =
        decode(bytes)?.using { img ->
            val scale = minOf(1.0, MAX_SIDE.toDouble() / max(img.cols(), img.rows()))
            if (scale < 1.0) {
                Mat().using { small ->
                    Imgproc.resize(img, small, Size(img.cols() * scale, img.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
                    encode(small, QUALITY)?.let { Jpeg(it, small.cols(), small.rows()) }
                }
            } else {
                encode(img, QUALITY)?.let { Jpeg(it, img.cols(), img.rows()) }
            }
        }

    /** JPEG at most [maxBytes]: lower quality first, then 80 % of the size, until it fits (or gives up). */
    fun shrink(
        bytes: ByteArray,
        maxBytes: Int,
    ): ByteArray? =
        decode(bytes)?.using { img ->
            var current = img.clone()
            try {
                repeat(SHRINK_ROUNDS) {
                    var q = QUALITY
                    while (q >= MIN_QUALITY) {
                        val out = encode(current, q)
                        if (out != null && out.size <= maxBytes) return@using out
                        q -= QUALITY_STEP
                    }
                    val next = Mat()
                    Imgproc.resize(current, next, Size(current.cols() * SHRINK_FACTOR, current.rows() * SHRINK_FACTOR), 0.0, 0.0, Imgproc.INTER_AREA)
                    current.release()
                    current = next
                }
                null
            } finally {
                current.release()
            }
        }

    private const val SHRINK_ROUNDS = 6

    private fun decode(bytes: ByteArray): Mat? =
        runCatching {
            MatOfByte(*bytes).using { buf -> Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR) }
        }.getOrNull()?.takeIf {
            if (it.empty()) {
                it.release()
                false
            } else {
                true
            }
        }

    private fun encode(
        img: Mat,
        quality: Int,
    ): ByteArray? =
        MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality).using { params ->
            MatOfByte().using { out -> if (Imgcodecs.imencode(".jpg", img, out, params)) out.toArray() else null }
        }
}
