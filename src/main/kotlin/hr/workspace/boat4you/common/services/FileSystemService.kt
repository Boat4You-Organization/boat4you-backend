package hr.workspace.boat4you.common.services

import hr.workspace.boat4you.common.exceptions.ResourceNotFound
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.name

@Service
class FileSystemService {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Value("\${application.upload.directory}")
    private lateinit var uploadDir: String

    @Value("\${application.upload.max-file-size-mb:10}")
    private val maxFileSizeMb: Long = 10

    private val allowedImageTypes =
        setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
        )

    fun saveImage(
        file: MultipartFile,
        subpath: String,
    ): Result<String> {
        try {
            // F1-020: materialize bytes once and validate against magic
            // bytes, not against the client-supplied Content-Type
            // header. The previous code trusted `file.contentType` —
            // anyone uploading `evil.php` with `Content-Type: image/jpeg`
            // would have passed the check and the file would have been
            // written under uploads/ as-is.
            validateFile(file)
            val bytes = file.bytes
            val imageType = detectImageType(bytes)
                ?: throw IllegalArgumentException("Invalid file type. Only images are allowed")
            val uniqueFilename = "${UUID.randomUUID()}.webp"
            val webpBytes =
                if (imageType == "image/webp") {
                    bytes
                } else {
                    ImageUtils.convertToWebP(ByteArrayInputStream(bytes), null, true)
                }
            if (webpBytes == null || webpBytes.isEmpty()) {
                throw IllegalArgumentException("Failed to convert image to WebP format")
            }
            return Result.success(saveFile(webpBytes, subpath, uniqueFilename).getOrElse { throw it })
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    fun saveImage(
        imageBytes: ByteArray,
        subpath: String,
    ): Result<String> {
        try {
            val imageType = validateImageBytes(imageBytes)
            val uniqueFilename = "${UUID.randomUUID()}.webp"
            val webpBytes =
                if (imageType == "image/webp") {
                    imageBytes
                } else {
                    ImageUtils.convertToWebP(ByteArrayInputStream(imageBytes), null, true)
                }
            if (webpBytes == null || webpBytes.isEmpty()) {
                throw IllegalArgumentException("Failed to convert image to WebP format")
            }
            return Result.success(saveFile(webpBytes, subpath, uniqueFilename).getOrElse { throw it })
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    fun savePdfFile(
        file: MultipartFile,
        subpath: String,
    ): Result<String> {
        try {
            // F1-020: magic-byte validation. `Content-Type` header is
            // attacker-controlled; the on-disk artefact's leading four
            // bytes `%PDF` are what we actually rely on.
            validateFile(file)
            val bytes = file.bytes
            if (!isPdfMagic(bytes)) {
                throw IllegalArgumentException("Invalid file type. Only PDF files are allowed")
            }
            val uniqueFilename = "${UUID.randomUUID()}.pdf"

            return saveFile(bytes, subpath, uniqueFilename)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    fun saveFile(
        image: ByteArray,
        subpath: String,
        customFilename: String,
    ): Result<String> {
        try {
            // Create upload directory if it doesn't exist
            val uploadPath = Paths.get(uploadDir, subpath)
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath)
            }

            // Save file with custom filename
            val filePath = uploadPath.resolve(customFilename)
            Files.copy(image.inputStream(), filePath, StandardCopyOption.REPLACE_EXISTING)

            return Result.success(subpath + "/" + filePath.name)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    fun saveFile(
        file: MultipartFile,
        subpath: String,
        customFilename: String,
    ): Result<String> {
        try {
            // Create upload directory if it doesn't exist
            val uploadPath = Paths.get(uploadDir, subpath)
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath)
            }

            // Save file with custom filename
            val filePath = uploadPath.resolve(customFilename)
            Files.copy(file.inputStream, filePath, StandardCopyOption.REPLACE_EXISTING)

            return Result.success(subpath + "/" + filePath.name)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private fun validateFile(file: MultipartFile) {
        if (file.isEmpty) {
            throw IllegalArgumentException("File is empty")
        }

        if (file.size > maxFileSizeMb * 1024 * 1024) {
            throw IllegalArgumentException("File size exceeds maximum allowed size of $maxFileSizeMb MB")
        }
    }

    private fun validateImageBytes(imageBytes: ByteArray): String {
        if (imageBytes.isEmpty()) {
            throw IllegalArgumentException("Image byte array is empty")
        }

        // Match the same configurable size cap that validateFile() applies to
        // MultipartFile uploads so internal byte-array uploads don't quietly
        // accept larger images than HTTP-bound ones.
        val maxBytes = maxFileSizeMb * 1024 * 1024
        if (imageBytes.size > maxBytes) {
            val sizeInMb = (imageBytes.size / 1024) / 1024
            throw IllegalArgumentException("Image size $sizeInMb MB exceeds maximum allowed size of $maxFileSizeMb MB")
        }

        return detectImageType(imageBytes)
            ?: throw IllegalArgumentException("Invalid image format")
    }

    /**
     * Magic-byte sniff for the three image formats we accept. Returns
     * the canonical content-type string if one matches, otherwise null.
     * F1-020: this is the single source of truth — both the HTTP
     * MultipartFile path and the internal ByteArray path route through
     * here, so a client cannot bypass the check by sending a lying
     * Content-Type header.
     */
    private fun detectImageType(bytes: ByteArray): String? =
        when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.sliceArray(0..7)
                .contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
            bytes.size >= 12 &&
                bytes.sliceArray(0..3).contentEquals("RIFF".toByteArray()) &&
                bytes.sliceArray(8..11).contentEquals("WEBP".toByteArray()) -> "image/webp"
            else -> null
        }?.takeIf { it in allowedImageTypes }

    /** PDF files always start with `%PDF` (0x25 0x50 0x44 0x46). */
    private fun isPdfMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x25.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() &&
            bytes[3] == 0x46.toByte()

    fun deleteFile(filename: String): Boolean {
        return try {
            val filePath = Paths.get(uploadDir, filename)
            Files.deleteIfExists(filePath)
        } catch (e: Exception) {
            log.error("Error deleting file $filename: ${e.message}")
            false
        }
    }

    fun getResourcePath(filename: String): Path {
        return Paths.get(uploadDir, filename)
    }

    fun getResourceFromPath(path: Path): Resource {
        try {
            if (!Files.exists(path)) {
                log.error("File not exists $path")
                throw ResourceNotFound()
            }
            val fileBytes = Files.readAllBytes(path)
            return ByteArrayResource(fileBytes)
        } catch (e: Exception) {
            log.error("Error accessing file at path $path: ${e.message}")
            throw ResourceNotFound()
        }
    }
}
