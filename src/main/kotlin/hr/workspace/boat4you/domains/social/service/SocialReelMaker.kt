package hr.workspace.boat4you.domains.social.service

import hr.workspace.boat4you.domains.social.jpa.SocialMedia
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialMediaOrigin
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists

/**
 * Makes a Reel from our own photos with ffmpeg on this server, no outside service: a vertical 1080 × 1920 MP4 (H.264,
 * 30 fps, silent AAC track) in which every photo is shown whole over a blurred copy of itself, zooms in slowly and
 * cross-fades into the next. 2–10 photos, about 3 s each (a Reel of 6–30 s, inside every network's short-video limits).
 * The result is stored like any other video ([SocialMediaStore.store], origin GENERATED).
 */
@Component
class SocialReelMaker(
    private val store: SocialMediaStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Why a Reel cannot be made on this node (no ffmpeg, or the shared media directory is missing), or null. */
    fun unavailableReason(): String? = store.unavailableReason() ?: if (ffmpegPresent) null else "ffmpeg is not installed on this server"

    private val ffmpegPresent: Boolean by lazy {
        runCatching {
            val p =
                ProcessBuilder("ffmpeg", "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            p.waitFor(CHECK_TIMEOUT_S, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    fun make(
        photos: List<SocialMedia>,
        uploadedBy: Long?,
        originRef: String? = null,
    ): SocialMedia {
        unavailableReason()?.let { throw SocialConfigException(it) }
        val images = photos.filter { it.kind == SocialMediaKind.IMAGE }.take(MAX_PHOTOS)
        if (images.size < MIN_PHOTOS) throw SocialValidationException("A Reel needs at least $MIN_PHOTOS photos")
        val out = Files.createTempFile("reel-", ".mp4")
        val err = Files.createTempFile("reel-", ".log")
        try {
            val p =
                ProcessBuilder(command(images.map { store.file(it) }, out))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(err.toFile())
                    .start()
            if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                throw SocialValidationException("Making the Reel took longer than $TIMEOUT_S s")
            }
            if (p.exitValue() != 0 || Files.size(out) == 0L) {
                log.warn("ffmpeg reel failed ({}): {}", p.exitValue(), Files.readString(err).takeLast(ERROR_TAIL))
                throw SocialValidationException("The Reel could not be made from these photos")
            }
            return Files.newInputStream(out).use {
                store.store(it, "reel.mp4", uploadedBy, SocialMediaOrigin.GENERATED, originRef ?: "reel:${images.joinToString(",") { m -> m.id.toString() }}".take(REF_MAX))
            }
        } finally {
            out.deleteIfExists()
            err.deleteIfExists()
        }
    }

    companion object {
        const val MIN_PHOTOS = 2
        const val MAX_PHOTOS = 10
        private const val WIDTH = 1080
        private const val HEIGHT = 1920
        private const val FPS = 30
        private const val SHOW_S = 3.2
        private const val FADE_S = 0.6
        private const val ZOOM_TO = 1.10
        private const val TIMEOUT_S = 240L
        private const val CHECK_TIMEOUT_S = 10L
        private const val ERROR_TAIL = 600
        private const val REF_MAX = 200

        /** Length of the finished Reel in seconds for [n] photos. */
        fun duration(n: Int): Double = n * SHOW_S - (n - 1) * FADE_S

        /** The ffmpeg command line: one input per photo, a silent audio input, one filter graph, one MP4. */
        fun command(
            images: List<Path>,
            out: Path,
        ): List<String> {
            val frames = (SHOW_S * FPS).toInt()
            val step = String.format(Locale.ROOT, "%.5f", (ZOOM_TO - 1) / frames)
            val graph = StringBuilder()
            images.indices.forEach { i ->
                graph.append(
                    "[$i:v]split[b$i][f$i];" +
                        "[b$i]scale=$WIDTH:$HEIGHT:force_original_aspect_ratio=increase,crop=$WIDTH:$HEIGHT,boxblur=24:2,eq=brightness=-0.08[bb$i];" +
                        "[f$i]scale=$WIDTH:$HEIGHT:force_original_aspect_ratio=decrease[ff$i];" +
                        "[bb$i][ff$i]overlay=(W-w)/2:(H-h)/2,scale=${WIDTH * 2}:${HEIGHT * 2}," +
                        "zoompan=z='min(zoom+$step,$ZOOM_TO)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=${WIDTH}x$HEIGHT:fps=$FPS," +
                        "setsar=1,format=yuv420p[v$i];",
                )
            }
            var last = "v0"
            for (i in 1 until images.size) {
                val offset = String.format(Locale.ROOT, "%.2f", i * (SHOW_S - FADE_S))
                graph.append("[$last][v$i]xfade=transition=fade:duration=$FADE_S:offset=$offset[x$i];")
                last = "x$i"
            }
            val total = String.format(Locale.ROOT, "%.2f", duration(images.size))
            return buildList {
                addAll(listOf("ffmpeg", "-y", "-loglevel", "error"))
                images.forEach { addAll(listOf("-i", it.toString())) }
                addAll(listOf("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100"))
                addAll(listOf("-filter_complex", graph.toString().trimEnd(';'), "-map", "[$last]", "-map", "${images.size}:a"))
                addAll(listOf("-t", total, "-r", FPS.toString(), "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p"))
                addAll(listOf("-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", "-f", "mp4", out.toString()))
            }
        }
    }
}
