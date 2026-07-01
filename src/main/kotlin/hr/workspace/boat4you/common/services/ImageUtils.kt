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

    /**
     * F4-009: every OpenCV `Mat` (and its `MatOfByte` / `MatOfInt` etc.
     * subclasses) is backed by an off-heap native buffer. The JVM
     * `finalize`-based safety net does eventually reclaim them, but on
     * a workload that processes thousands of images a day VM3 native
     * memory grew ~1.5 GB/day before OOM-kill — finalize ran far behind
     * allocation. The original code only called `.release()` on the
     * happy path and only for some of the Mats, so any exception or
     * early return leaked the rest.
     *
     * This extension mirrors `java.io.Closeable.use { }` but for
     * OpenCV's hand-rolled `.release()` API. `runCatching` on release
     * ensures one failing release does not mask the original exception
     * or skip releases of siblings further up the stack.
     */
    private inline fun <T : Mat, R> T.use(block: (T) -> R): R {
        try {
            return block(this)
        } finally {
            runCatching { this.release() }
        }
    }

    fun convertToWebP(
        inputStream: InputStream,
        quality: Int? = 80,
        lossless: Boolean = false,
    ): ByteArray? =
        try {
            val inputBytes = inputStream.readBytes()
            MatOfByte(*inputBytes).use { matOfByte ->
                Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR).use { image ->
                    if (image.empty()) {
                        // callers get null back and log the failure with context (URL/filename)
                        log.warn("Could not decode image from input stream")
                        null
                    } else {
                        MatOfInt().use { params ->
                            params.fromArray(
                                Imgcodecs.IMWRITE_WEBP_QUALITY,
                                if (lossless) 100 else quality!!,
                            )
                            MatOfByte().use { outputMat ->
                                val success = Imgcodecs.imencode(".webp", image, outputMat, params)
                                if (success) outputMat.toArray() else null
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error converting image to WebP", e)
            null
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

        return Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR).use { originalMat ->
            if (originalMat.empty()) {
                // F4-013: file path stays in the log, never in the
                // exception message. Path leaks via the message used
                // to surface in 500 responses through the F1-055
                // catch-all (now closed in B5) — defence-in-depth
                // keeps the message generic even if a future leak
                // channel reappears.
                log.error("Could not load image from path: {}", imagePath)
                throw IllegalArgumentException("Could not load image")
            }

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

            Mat().use { resizedMat ->
                val newSize = Size(targetWidth.toDouble(), computedHeight.toDouble())
                Imgproc.resize(originalMat, resizedMat, newSize, 0.0, 0.0, interpolationMethod)

                MatOfInt(Imgcodecs.IMWRITE_WEBP_QUALITY, quality).use { encodeParams ->
                    MatOfByte().use { matOfByte ->
                        val success = Imgcodecs.imencode(".webp", resizedMat, matOfByte, encodeParams)
                        if (!success) {
                            throw RuntimeException("Failed to encode image to WebP format")
                        }
                        matOfByte.toArray()
                    }
                }
            }
        }
    }

}
