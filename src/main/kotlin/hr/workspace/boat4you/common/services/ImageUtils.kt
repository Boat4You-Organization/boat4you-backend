package hr.workspace.boat4you.common.services

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import kotlin.math.roundToInt

object ImageUtils {
    init {
        nu.pattern.OpenCV.loadLocally()
    }

    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun convertToWebP(
        inputStream: InputStream,
        quality: Int? = 80,
        lossless: Boolean = false,
    ): ByteArray? {
        return try {
            // Read input stream to byte array
            val inputBytes = inputStream.readBytes()

            // Decode image from byte array
            val matOfByte = MatOfByte(*inputBytes)
            val image = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR)

            if (image.empty()) {
                println("Could not decode image from input stream")
                return null
            }

            // Prepare WebP encoding parameters
            val params = MatOfInt()
            if (lossless) {
                params.fromArray(
                    Imgcodecs.IMWRITE_WEBP_QUALITY,
                    100,
                )
            } else {
                params.fromArray(
                    Imgcodecs.IMWRITE_WEBP_QUALITY,
                    quality!!,
                )
            }

            // Encode to WebP
            val outputMat = MatOfByte()
            val success = Imgcodecs.imencode(".webp", image, outputMat, params)

            // Clean up
            image.release()
            matOfByte.release()

            if (success) {
                outputMat.toArray()
            } else {
                null
            }
        } catch (e: Exception) {
            log.error("Error converting image to WebP", e)
            null
        }
    }

    fun resizeImage(
        imagePath: String,
        targetWidth: Int,
        targetHeight: Int? = null,
        quality: Int = 80,
        interpolationMethod: Int = Imgproc.INTER_LINEAR,
    ): ByteArray {
        require(targetWidth > 0) { "targetWidth must be > 0" }
        require(quality in 0..100) { "quality must be in 0..100" }

        // Read image (OpenCV automatically detects format including WebP)
        val originalMat = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR)

        if (originalMat.empty()) {
            throw IllegalArgumentException("Could not load image from path: $imagePath")
        }

        // Calculate height if null, while keeping aspect ratio
        val srcW = originalMat.cols()
        val srcH = originalMat.rows()
        require(srcW > 0 && srcH > 0) { "Invalid source image dimensions: ${srcW}x$srcH" }

        val computedHeight =
            targetHeight?.also {
                require(it > 0) { "targetHeight must be > 0" }
            } ?: run {
                // height = width * (srcH/srcW), rounded to nearest int, but at least 1
                val h = ((targetWidth.toDouble() * srcH.toDouble()) / srcW.toDouble()).roundToInt()
                maxOf(1, h)
            }

        // Create destination matrix
        val resizedMat = Mat()

        // Resize image
        val newSize = Size(targetWidth.toDouble(), computedHeight.toDouble())
        Imgproc.resize(originalMat, resizedMat, newSize, 0.0, 0.0, interpolationMethod)

        // Encode to WebP format
        val matOfByte = MatOfByte()
        val encodeParams = MatOfInt(Imgcodecs.IMWRITE_WEBP_QUALITY, quality)

        val success = Imgcodecs.imencode(".webp", resizedMat, matOfByte, encodeParams)

        // Clean up memory
        originalMat.release()
        resizedMat.release()

        if (!success) {
            throw RuntimeException("Failed to encode image to WebP format")
        }

        return matOfByte.toArray()
    }

    fun resizeImage(
        inputBytes: ByteArray,
        width: Int,
        height: Int,
        quality: Int = 90,
    ): ByteArray {
        // Decode from byte array
        val matOfByte = MatOfByte(*inputBytes)
        val originalMat = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR)

        // Resize
        val resizedMat = Mat()
        val newSize = Size(width.toDouble(), height.toDouble())
        Imgproc.resize(originalMat, resizedMat, newSize, 0.0, 0.0, Imgproc.INTER_LINEAR)

        // Encode to WebP
        val outputMatOfByte = MatOfByte()
        val encodeParams = MatOfInt(Imgcodecs.IMWRITE_WEBP_QUALITY, quality)
        Imgcodecs.imencode(".webp", resizedMat, outputMatOfByte, encodeParams)

        // Clean up
        originalMat.release()
        resizedMat.release()

        return outputMatOfByte.toArray()
    }
}
