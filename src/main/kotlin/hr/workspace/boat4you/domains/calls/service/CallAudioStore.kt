package hr.workspace.boat4you.domains.calls.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset

/**
 * Call recordings (post_call_audio MP3) under the shared upload directory: `calls/<yyyy>/<mm>/<conversation>.mp3`. The
 * conversation id is reduced to [A-Za-z0-9_-] so no webhook value can leave the directory.
 */
@Component
class CallAudioStore(
    @Value("\${application.upload.directory}") private val uploadDir: String,
) {
    fun save(
        conversationId: String,
        mp3: ByteArray,
        at: Instant,
    ): String {
        val safe = conversationId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(100).ifEmpty { "call" }
        val utc = at.atOffset(ZoneOffset.UTC)
        val relative = "calls/%04d/%02d/%s.mp3".format(utc.year, utc.monthValue, safe)
        val file = resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, mp3)
        return relative
    }

    fun resolve(relative: String): Path {
        val root = Paths.get(uploadDir).toAbsolutePath().normalize()
        val file = root.resolve(relative).normalize()
        require(file.startsWith(root.resolve("calls"))) { "audio path outside calls/" }
        return file
    }

    fun delete(relative: String) {
        Files.deleteIfExists(resolve(relative))
    }
}
