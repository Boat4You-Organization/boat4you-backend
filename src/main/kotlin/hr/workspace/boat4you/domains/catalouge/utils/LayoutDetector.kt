package hr.workspace.boat4you.domains.catalouge.utils

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What [LayoutDetector] measured on one image; [layout] is the decision, [score] only ranks candidates. */
data class LayoutScore(
    val width: Int,
    val height: Int,
    /** Pixels with min(B, G, R) >= [LayoutDetector.WHITE]. */
    val whiteFraction: Double,
    /** White pixels plus a uniform light-grey background (the most frequent light, colourless colour), if there is one. */
    val backgroundFraction: Double,
    /** Background inside the bounding box of the drawing: a plan keeps white inside its outline, a photo with white bars does not. */
    val contentBackgroundFraction: Double,
    /** Mean HSV saturation (0..1) of the pixels that are not background. */
    val meanSaturation: Double,
    /** Canny edge pixels / all pixels, at [LayoutDetector.SIZE] px. Reported for the review, not part of the decision. */
    val edgeDensity: Double,
    /** Connected dark regions (lines, walls, text) of at least [LayoutDetector.MIN_COMPONENT_PIXELS]. Reported only. */
    val darkComponents: Int,
    val layout: Boolean,
    val score: Double,
)

/**
 * A layout drawing (deck / floor plan) recognised by its pixels, for the plans whose file name says nothing
 * ([LayoutImageRules] misses "main.png", "n4.jpg"): Bright Future 4348 (16.10.2026) shows its plan in the photo gallery.
 * Used only by the admin scan POST /admin/yachts/images/detect-layout (dry run, review, then apply sets
 * yacht_image.layout_override), never on a public request: one decode is ~0.1 s and tens of MB of native memory.
 *
 * The image is decoded and shrunk to [SIZE] px on its long side, then: a plan is a drawing on a white (or uniform
 * light-grey) sheet - a large background share, background left INSIDE the drawing's bounding box (around the hull
 * outline, inside cabins), and little colour. Calibrated 16.10.2026 on 96 stored images from the public API (36 plans:
 * 35 that LayoutImageRules flags + the Bright Future plan; 60 photos of the same boats: their main photos, a second photo
 * of 12 and the Bright Future gallery): 36 / 36 plans, 0 / 60 photos (DEPLOY_NOTES 16.10.2026). The photos closest to
 * the line are boats framed by white bars (white 0.25-0.27 but 0.00-0.03 inside the box) and bright interiors (white
 * inside the box up to 0.14, overall at most 0.15); the faintest plans have white 0.29 overall / 0.17 inside.
 */
object LayoutDetector {
    init {
        nu.pattern.OpenCV.loadLocally()
    }

    const val SIZE = 256
    const val WHITE = 235

    /** A light-grey sheet: the most frequent colour (16 levels per channel) with every channel >= this... */
    private const val LIGHT_BACKGROUND_MIN = 180

    /** ... and at most this between its channels (colourless) ... */
    private const val LIGHT_BACKGROUND_MAX_CHROMA = 24

    /** ... covering at least this share of the image; then pixels within [BACKGROUND_TOLERANCE] of it are background. */
    private const val LIGHT_BACKGROUND_MIN_SHARE = 0.20
    private const val BACKGROUND_TOLERANCE = 16

    /** A row / column belongs to the drawing when more than this share of it is not background. */
    private const val CONTENT_LINE_SHARE = 0.02
    private const val DARK = 110.0
    const val MIN_COMPONENT_PIXELS = 3

    // The decision (calibration 16.10.2026, see the class comment)
    const val MIN_BACKGROUND = 0.25
    const val MIN_CONTENT_BACKGROUND = 0.15
    const val MAX_SATURATION = 0.45

    /** The image file at [path], or null when it cannot be decoded. */
    fun score(path: String): LayoutScore? = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR).use { image -> if (image.empty()) null else score(image) }

    /** An 8-bit BGR image. */
    fun score(image: Mat): LayoutScore {
        val scale = SIZE.toDouble() / max(image.cols(), image.rows())
        return Mat().use { small ->
            if (scale < 1.0) {
                val size = Size(max(1, (image.cols() * scale).roundToInt()).toDouble(), max(1, (image.rows() * scale).roundToInt()).toDouble())
                Imgproc.resize(image, small, size, 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                image.copyTo(small)
            }
            val pixels = ByteArray((small.total() * small.channels()).toInt())
            small.get(0, 0, pixels)
            val (edges, components) = lines(small)
            measure(pixels, small.cols(), small.rows(), image.cols(), image.rows(), edges, components)
        }
    }

    private fun lines(small: Mat): Pair<Double, Int> =
        Mat().use { gray ->
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
            val edgeDensity =
                Mat().use { edges ->
                    Imgproc.Canny(gray, edges, 50.0, 150.0)
                    Core.countNonZero(edges).toDouble() / gray.total()
                }
            val components =
                Mat().use { dark ->
                    Imgproc.threshold(gray, dark, DARK, 255.0, Imgproc.THRESH_BINARY_INV)
                    darkComponents(dark)
                }
            edgeDensity to components
        }

    /** Connected regions of [dark] (a binary mask) of at least [MIN_COMPONENT_PIXELS]. */
    private fun darkComponents(dark: Mat): Int {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val n = Imgproc.connectedComponentsWithStats(dark, labels, stats, centroids)
            return (1 until n).count { stats.get(it, Imgproc.CC_STAT_AREA)[0] >= MIN_COMPONENT_PIXELS }
        } finally {
            listOf(labels, stats, centroids).forEach { runCatching { it.release() } }
        }
    }

    /** The pixel measures on [bgr] (w x h, 3 bytes per pixel, BGR) - pure Kotlin, the unit-testable core. */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    internal fun measure(
        bgr: ByteArray,
        w: Int,
        h: Int,
        originalWidth: Int,
        originalHeight: Int,
        edgeDensity: Double,
        darkComponents: Int,
    ): LayoutScore {
        val n = w * h
        val lightSheet = lightSheetColour(bgr, n)
        val background = BooleanArray(n)
        var white = 0
        var satSum = 0.0
        var satCount = 0
        for (i in 0 until n) {
            val b = bgr[3 * i].toInt() and 0xFF
            val g = bgr[3 * i + 1].toInt() and 0xFF
            val r = bgr[3 * i + 2].toInt() and 0xFF
            val lo = min(b, min(g, r))
            val hi = max(b, max(g, r))
            val isWhite = lo >= WHITE
            if (isWhite) white++
            val nearSheet =
                lightSheet != null &&
                    abs(b - lightSheet[0]) <= BACKGROUND_TOLERANCE &&
                    abs(g - lightSheet[1]) <= BACKGROUND_TOLERANCE &&
                    abs(r - lightSheet[2]) <= BACKGROUND_TOLERANCE
            background[i] = isWhite || nearSheet
            if (!background[i]) {
                satSum += if (hi == 0) 0.0 else (hi - lo).toDouble() / hi
                satCount++
            }
        }
        val backgroundCount = background.count { it }
        val contentBackground = contentBackground(background, w, h)
        val whiteFraction = white.toDouble() / n
        val backgroundFraction = backgroundCount.toDouble() / n
        val meanSaturation = if (satCount == 0) 0.0 else satSum / satCount
        val layout =
            backgroundFraction >= MIN_BACKGROUND &&
                contentBackground >= MIN_CONTENT_BACKGROUND &&
                meanSaturation <= MAX_SATURATION
        return LayoutScore(
            width = originalWidth,
            height = originalHeight,
            whiteFraction = whiteFraction,
            backgroundFraction = backgroundFraction,
            contentBackgroundFraction = contentBackground,
            meanSaturation = meanSaturation,
            edgeDensity = edgeDensity,
            darkComponents = darkComponents,
            layout = layout,
            score = min(backgroundFraction / MIN_BACKGROUND, contentBackground / MIN_CONTENT_BACKGROUND) * (1.0 - meanSaturation),
        )
    }

    /** The uniform light-grey sheet colour (B, G, R bin centres), or null when the most frequent colour is not one. */
    private fun lightSheetColour(
        bgr: ByteArray,
        n: Int,
    ): IntArray? {
        val bins = IntArray(BINS * BINS * BINS)
        for (i in 0 until n) {
            val b = (bgr[3 * i].toInt() and 0xFF) / BIN_WIDTH
            val g = (bgr[3 * i + 1].toInt() and 0xFF) / BIN_WIDTH
            val r = (bgr[3 * i + 2].toInt() and 0xFF) / BIN_WIDTH
            bins[(b * BINS + g) * BINS + r]++
        }
        val top = bins.indices.maxBy { bins[it] }
        if (bins[top] < LIGHT_BACKGROUND_MIN_SHARE * n) return null
        val colour = intArrayOf(top / (BINS * BINS), (top / BINS) % BINS, top % BINS).map { it * BIN_WIDTH + BIN_WIDTH / 2 }
        val light = colour.min() >= LIGHT_BACKGROUND_MIN && colour.max() - colour.min() <= LIGHT_BACKGROUND_MAX_CHROMA
        return if (light) colour.toIntArray() else null
    }

    /** Background share inside the bounding box of the rows / columns that carry the drawing; 0 when there is none. */
    private fun contentBackground(
        background: BooleanArray,
        w: Int,
        h: Int,
    ): Double {
        val rowContent = IntArray(h)
        val colContent = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!background[y * w + x]) {
                    rowContent[y]++
                    colContent[x]++
                }
            }
        }
        val rows = (0 until h).filter { rowContent[it] > CONTENT_LINE_SHARE * w }
        val cols = (0 until w).filter { colContent[it] > CONTENT_LINE_SHARE * h }
        if (rows.isEmpty() || cols.isEmpty()) return 0.0
        var inside = 0
        var total = 0
        for (y in rows.first()..rows.last()) {
            for (x in cols.first()..cols.last()) {
                total++
                if (background[y * w + x]) inside++
            }
        }
        return inside.toDouble() / total
    }

    private const val BIN_WIDTH = 16
    private const val BINS = 256 / BIN_WIDTH

    /** OpenCV's native buffers released on every path (ImageUtils F4-009). */
    private inline fun <R> Mat.use(block: (Mat) -> R): R {
        try {
            return block(this)
        } finally {
            runCatching { release() }
        }
    }
}
