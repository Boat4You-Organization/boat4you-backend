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
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Files of Objave under `social.media-dir`: `{token}/media.jpg` (every photo re-encoded as JPEG, longest side ≤ 2160 px:
 * Instagram takes JPEG only) or `{token}/media.mp4|mov` plus `{token}/thumb.jpg` (ffmpeg, when installed). Served
 * publicly at [publicUrl] so Instagram and Threads, which fetch by URL, can read them; the 30-character token makes
 * the URL unguessable. Type is decided by magic bytes, never by the client's Content-Type or name.
 *
 * Both nodes must see the same files (NFS): the directory must hold the marker file `.objave-shared`, created by the
 * deploy (infra social_env_set.sh), never by this code. Without it nothing is stored, served or generated
 * ([unavailableReason]), so a node with the share not mounted fails loudly instead of writing to its local disk.
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

    /** Why media cannot be stored or served on this node (the shared directory is not there), or null. */
    fun unavailableReason(): String? =
        if (Files.isRegularFile(root.resolve(SHARED_MARKER))) null else "SOCIAL_MEDIA_DIR is not the shared directory (marker $SHARED_MARKER missing)"

    fun requireShared() {
        unavailableReason()?.let { throw SocialConfigException(it) }
    }

    /** Store a stream (admin upload, automation upload, downloaded URL, generated video) and its row. */
    fun store(
        input: InputStream,
        originalName: String?,
        uploadedBy: Long?,
        origin: SocialMediaOrigin = SocialMediaOrigin.UPLOAD,
        originRef: String? = null,
    ): SocialMedia {
        requireShared()
        val tmp = Files.createTempFile(root, "upload-", ".part")
        try {
            val spooled = spool(input, tmp)
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
                    this.sha256 = spooled.sha256
                }
            if (type.kind == SocialMediaKind.IMAGE) storeImage(tmp, dir, media, spooled.size) else storeVideo(tmp, dir, type, media, spooled.size)
            return repo.save(media)
        } finally {
            tmp.deleteIfExists()
        }
    }

    private class Spooled(
        val size: Long,
        val sha256: String,
    )

    /** Copies the stream to [tmp] up to the video limit, hashing it on the way. */
    private fun spool(
        input: InputStream,
        tmp: Path,
    ): Spooled {
        val limit = props.maxVideoMb * MB
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newOutputStream(tmp).use { out ->
            val buf = ByteArray(BUFFER)
            var n = input.read(buf)
            while (n >= 0) {
                size += n
                if (size > limit) throw SocialValidationException("File larger than ${props.maxVideoMb} MB")
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
                n = input.read(buf)
            }
        }
        if (size == 0L) throw SocialValidationException("Empty file")
        return Spooled(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Any photo → JPEG ≤ 2160 px (Instagram takes JPEG only). */
    private fun storeImage(
        tmp: Path,
        dir: Path,
        media: SocialMedia,
        size: Long,
    ) {
        if (size > props.maxImageMb * MB) throw SocialValidationException("Photo larger than ${props.maxImageMb} MB")
        // A small file can still claim a huge canvas (decompression bomb): the header decides before anything is decoded.
        val (w, h) = SocialImages.headerSize(tmp) ?: throw SocialValidationException("The photo cannot be read")
        if (w.toLong() * h > MAX_PIXELS) throw SocialValidationException("Photo larger than ${MAX_PIXELS / 1_000_000} megapixels")
        val jpeg = SocialImages.normalize(Files.readAllBytes(tmp)) ?: throw SocialValidationException("The photo cannot be read")
        Files.write(dir.resolve("media.jpg"), jpeg.bytes)
        media.kind = SocialMediaKind.IMAGE
        media.mimeType = "image/jpeg"
        media.storagePath = "${media.token}/media.jpg"
        media.sizeBytes = jpeg.bytes.size.toLong()
        media.width = jpeg.width
        media.height = jpeg.height
    }

    /** The video as uploaded, plus size / duration (ffprobe) and a JPEG frame (ffmpeg) when those tools exist. */
    private fun storeVideo(
        tmp: Path,
        dir: Path,
        type: Sniffed,
        media: SocialMedia,
        size: Long,
    ) {
        val name = "media.${type.ext}"
        val file = dir.resolve(name)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        media.kind = SocialMediaKind.VIDEO
        media.mimeType = type.mime
        media.storagePath = "${media.token}/$name"
        media.sizeBytes = size
        probeVideo(file)?.let { (w, h, d) ->
            media.width = w
            media.height = h
            media.durationSec = d
        }
        if (thumbnail(file, dir.resolve("thumb.jpg"))) media.thumbnailPath = "${media.token}/thumb.jpg"
    }

    /**
     * Download a picture or video for the automation / the AI. A catalogue photo of our own API (`publicApiUrl` or
     * `selfBaseUrl` + /public/image/…) is always allowed; any other address must be https and resolve to public addresses
     * only (SSRF guard).
     */
    fun storeFromUrl(
        url: String,
        uploadedBy: Long?,
        origin: SocialMediaOrigin = SocialMediaOrigin.URL,
        originRef: String? = null,
    ): SocialMedia {
        requireShared()
        val uri = runCatching { URI(url.trim()).normalize() }.getOrNull() ?: throw SocialValidationException("Bad media URL")
        if (!isOwnImage(uri)) assertPublicHttps(uri)
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

    private fun isOwnImage(uri: URI): Boolean =
        uri.userInfo == null &&
            listOf(props.publicApiUrl, props.selfBaseUrl).any { base ->
                val b = runCatching { URI(base.trimEnd('/')) }.getOrNull()
                b != null && uri.scheme == b.scheme && uri.host.equals(b.host, ignoreCase = true) && uri.port == b.port &&
                    uri.rawPath.orEmpty().startsWith("${b.rawPath.orEmpty()}/public/image/")
            }

    private fun assertPublicHttps(uri: URI) {
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) throw SocialValidationException("Media URL must be https")
        val addresses = runCatching { InetAddress.getAllByName(uri.host).toList() }.getOrDefault(emptyList())
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw SocialValidationException("Media URL host is not public")
    }

    private fun isPublic(a: InetAddress): Boolean =
        !(a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) &&
            !(a is Inet4Address && (a.address[0].toInt() and 0xff) == CGNAT_FIRST && (a.address[1].toInt() and 0xc0) == CGNAT_SECOND_MASK) &&
            // 0.0.0.0/8 ("this network") and 198.18.0.0/15 (benchmarking) are not public either.
            !(a is Inet4Address && ((a.address[0].toInt() and 0xff) == 0 || ((a.address[0].toInt() and 0xff) == BENCH_FIRST && (a.address[1].toInt() and 0xfe) == BENCH_SECOND))) &&
            !(a.hostAddress.startsWith("fc") || a.hostAddress.startsWith("fd"))

    /** ffprobe's JSON goes to a temp file (not a pipe that blocks until exit), so the timeout really ends a stuck run. */
    private fun probeVideo(path: Path): Triple<Int?, Int?, Int?>? {
        val out = Files.createTempFile("ffprobe-", ".json")
        return try {
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
                    ).redirectOutput(out.toFile())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                if (!finished(p)) return null
                val json = jacksonObjectMapper().readTree(out.toFile())
                val stream = json.path("streams").path(0)
                Triple(
                    stream.path("width").asInt(0).takeIf { it > 0 },
                    stream.path("height").asInt(0).takeIf { it > 0 },
                    json.path("format").path("duration").asText("").toDoubleOrNull()?.roundToInt(),
                )
            }.onFailure { log.info("ffprobe unavailable or failed: {}", it.message) }.getOrNull()
        } finally {
            out.deleteIfExists()
        }
    }

    private fun thumbnail(
        video: Path,
        out: Path,
    ): Boolean =
        runCatching {
            val p =
                ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-ss", "1", "-i", video.toString(), "-frames:v", "1", "-vf", "scale=720:-2", out.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            (finished(p) && Files.exists(out)).also { ok -> if (!ok) out.deleteIfExists() }
        }.getOrDefault(false)

    /** Waits up to [PROBE_TIMEOUT_S]; a process still running then is killed and counts as failed, like a non-zero exit. */
    private fun finished(p: Process): Boolean {
        if (!p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return false
        }
        return p.exitValue() == 0
    }

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
        private const val BENCH_FIRST = 198
        private const val BENCH_SECOND = 18
        private const val MAX_PIXELS = 50_000_000L
        const val SHARED_MARKER = ".objave-shared"
        private const val NAME_MAX = 120

        /** JPEG FF D8 FF, PNG 89 50 4E 47, WebP RIFF....WEBP, MP4/MOV ....ftyp (qt = MOV). */
        fun sniff(head: ByteArray): Sniffed? =
            when {
                startsWith(head, 0, 0xFF, 0xD8, 0xFF) -> Sniffed(SocialMediaKind.IMAGE, "image/jpeg", "jpg")
                startsWith(head, 0, 0x89, 'P'.code, 'N'.code, 'G'.code) -> Sniffed(SocialMediaKind.IMAGE, "image/png", "png")
                ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP") -> Sniffed(SocialMediaKind.IMAGE, "image/webp", "webp")
                ascii(head, 4, "ftypqt") -> Sniffed(SocialMediaKind.VIDEO, "video/quicktime", "mov")
                ascii(head, 4, "ftyp") -> Sniffed(SocialMediaKind.VIDEO, "video/mp4", "mp4")
                else -> null
            }

        private fun startsWith(
            head: ByteArray,
            at: Int,
            vararg bytes: Int,
        ) = head.size >= at + bytes.size && bytes.indices.all { head[at + it] == bytes[it].toByte() }

        private fun ascii(
            head: ByteArray,
            at: Int,
            s: String,
        ) = startsWith(head, at, *s.map { it.code }.toIntArray())

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

    /**
     * Width and height from the file's header, without decoding the pixels: JPEG and PNG through ImageIO's readers
     * (they read the header only), WebP from its VP8 / VP8L / VP8X chunk. Null when the header cannot be read.
     */
    fun headerSize(file: Path): Pair<Int, Int>? = imageIoSize(file) ?: webpSize(Files.newInputStream(file).use { it.readNBytes(WEBP_HEAD) })

    private fun imageIoSize(file: Path): Pair<Int, Int>? =
        runCatching {
            ImageIO.createImageInputStream(file.toFile())?.use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
                try {
                    reader.input = input
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()

    @Suppress("MagicNumber", "ReturnCount") // byte offsets of the WebP container (RIFF), as the format defines them; one return per chunk kind
    fun webpSize(h: ByteArray): Pair<Int, Int>? {
        if (h.size < WEBP_HEAD || String(h, 0, 4, Charsets.US_ASCII) != "RIFF" || String(h, 8, 4, Charsets.US_ASCII) != "WEBP") return null

        fun u(i: Int) = h[i].toInt() and 0xff
        return when (String(h, 12, 4, Charsets.US_ASCII)) {
            "VP8X" -> (1 + (u(24) or (u(25) shl 8) or (u(26) shl 16))) to (1 + (u(27) or (u(28) shl 8) or (u(29) shl 16)))
            "VP8L" -> {
                if (u(20) != 0x2F) return null
                val bits = u(21) or (u(22) shl 8) or (u(23) shl 16) or (u(24) shl 24)
                (1 + (bits and 0x3FFF)) to (1 + ((bits shr 14) and 0x3FFF))
            }
            "VP8 " -> {
                if (u(23) != 0x9D || u(24) != 0x01 || u(25) != 0x2A) return null
                ((u(26) or (u(27) shl 8)) and 0x3FFF) to ((u(28) or (u(29) shl 8)) and 0x3FFF)
            }
            else -> null
        }
    }

    private const val WEBP_HEAD = 30

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
