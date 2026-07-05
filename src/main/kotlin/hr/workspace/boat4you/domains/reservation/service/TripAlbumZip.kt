package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.services.FileSystemService
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One album photo to stream into a ZIP: DB id + on-disk (NFS) path. */
data class TripAlbumFile(val id: Long, val filePath: String)

/**
 * Streams a trip album to a ZIP straight onto the response OutputStream —
 * one photo at a time via [Files.copy] (constant heap, ~8 KB buffer). Called
 * from the controller's StreamingResponseBody, i.e. AFTER the request thread
 * returns, so it holds no DB connection and never buffers the whole album in
 * heap (both would be fatal on cusma2, the single no-swap API node).
 */
object TripAlbumZip {
    private val log = LoggerFactory.getLogger(TripAlbumZip::class.java)

    fun write(files: List<TripAlbumFile>, fileSystemService: FileSystemService, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            files.forEachIndexed { index, file ->
                val path = fileSystemService.getResourcePath(file.filePath)
                if (!Files.exists(path)) {
                    log.warn("Trip album: missing file for photo ${file.id} at ${file.filePath}")
                    return@forEachIndexed
                }
                runCatching {
                    zos.putNextEntry(ZipEntry("photo-${"%03d".format(index + 1)}-${file.id}.webp"))
                    Files.copy(path, zos)
                    zos.closeEntry()
                }.onFailure { log.warn("Trip album: failed to add photo ${file.id}", it) }
            }
        }
    }
}
