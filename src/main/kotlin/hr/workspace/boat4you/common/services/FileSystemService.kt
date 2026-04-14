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
            val imageType = validateImageFile(file)
            val uniqueFilename = "${UUID.randomUUID()}.webp"
            val webpBytes =
                if (imageType == "image/webp") {
                    file.bytes
                } else {
                    ImageUtils.convertToWebP(file.inputStream, null, true)
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
            validatePdfFile(file)
            val uniqueFilename = "${UUID.randomUUID()}.pdf"

            return saveFile(file, subpath, uniqueFilename)
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

    private fun validateImageFile(file: MultipartFile): String {
        validateFile(file)

        // Check content type
        val contentType = file.contentType
        if (contentType == null || !allowedImageTypes.contains(contentType)) {
            throw IllegalArgumentException("Invalid file type. Only images are allowed")
        }

        return contentType
    }

    private fun validatePdfFile(file: MultipartFile) {
        validateFile(file)

        // Check content type
        val contentType = file.contentType
        if (contentType == null || contentType != "application/pdf") {
            throw IllegalArgumentException("Invalid file type. Only PDF files are allowed")
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

    // You'll also need a validation method for byte arrays:
    private fun validateImageBytes(imageBytes: ByteArray): String {
        if (imageBytes.isEmpty()) {
            throw IllegalArgumentException("Image byte array is empty")
        }

        // Optional: Check file size (example: max 15MB)
        if (imageBytes.size > 15 * 1024 * 1024) {
            val sizeInMb = (imageBytes.size / 1024) / 1024
            throw IllegalArgumentException("Image size $sizeInMb MB exceeds maximum allowed size")
        }

        // Optional: Basic image format validation by checking magic bytes
        val contentType =
            when {
                imageBytes.size >= 2 && imageBytes[0] == 0xFF.toByte() && imageBytes[1] == 0xD8.toByte() -> "image/jpeg" // JPEG
                imageBytes.size >= 8 && imageBytes.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png" // PNG
                imageBytes.size >= 12 &&
                    imageBytes.sliceArray(0..3).contentEquals("RIFF".toByteArray()) &&
                    imageBytes.sliceArray(8..11).contentEquals("WEBP".toByteArray())
                -> "image/webp" // WebP
                else -> null
            }

        if (contentType == null || !allowedImageTypes.contains(contentType)) {
            throw IllegalArgumentException("Invalid image format")
        }

        return contentType
    }

    private fun getFileExtension(filename: String): String {
        return filename.substringAfterLast('.', "jpg")
    }

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
