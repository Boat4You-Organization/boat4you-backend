package hr.workspace.boat4you.domains.catalouge.utils

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.nio.file.Files

/**
 * The visual layout detector on small synthetic images (the real calibration, 96 stored images, is
 * LayoutDetectorCalibrationTest with LAYOUT_CALIBRATION_DIR set): a deck plan is a line drawing on a white or light-grey
 * sheet; a photo is colour all over - also when the partner framed it with white bars.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LayoutDetectorTest {
    @BeforeAll
    fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

    private val black = Scalar(0.0, 0.0, 0.0)

    private fun sheet(
        w: Int,
        h: Int,
        grey: Double = 255.0,
    ) = Mat(h, w, CvType.CV_8UC3, Scalar(grey, grey, grey))

    /** A hull outline from above (pointed bow), cabin walls and a saloon table, black lines. */
    private fun plan(sheet: Mat): Mat {
        val w = sheet.cols().toDouble()
        val h = sheet.rows().toDouble()
        val hull =
            MatOfPoint(
                Point(w * 0.05, h * 0.25),
                Point(w * 0.70, h * 0.15),
                Point(w * 0.95, h * 0.50),
                Point(w * 0.70, h * 0.85),
                Point(w * 0.05, h * 0.75),
            )
        Imgproc.polylines(sheet, listOf(hull), true, black, 2)
        listOf(0.20, 0.35, 0.50, 0.62).forEach { x -> Imgproc.line(sheet, Point(w * x, h * 0.2), Point(w * x, h * 0.8), black, 1) }
        Imgproc.line(sheet, Point(w * 0.05, h * 0.5), Point(w * 0.62, h * 0.5), black, 1)
        Imgproc.rectangle(sheet, Rect((w * 0.38).toInt(), (h * 0.40).toInt(), (w * 0.08).toInt(), (h * 0.20).toInt()), black, 1)
        return sheet
    }

    private fun noise(
        w: Int,
        h: Int,
    ): Mat = Mat(h, w, CvType.CV_8UC3).also { Core.randu(it, 0.0, 255.0) }

    @Test
    fun `a white sheet with a line drawing is a layout`() {
        val score = LayoutDetector.score(plan(sheet(1056, 420)))

        score.layout shouldBe true
        score.whiteFraction shouldBeGreaterThan 0.8
        score.contentBackgroundFraction shouldBeGreaterThan 0.5
        score.meanSaturation shouldBeLessThan 0.05
        score.width shouldBe 1056
        score.height shouldBe 420
    }

    @Test
    fun `a uniform light-grey sheet counts as background`() {
        val score = LayoutDetector.score(plan(sheet(900, 400, grey = 222.0)))

        score.whiteFraction shouldBeLessThan 0.01
        score.backgroundFraction shouldBeGreaterThan 0.8
        score.layout shouldBe true
    }

    @Test
    fun `a noisy colourful image is a photo`() {
        val score = LayoutDetector.score(noise(256, 192)) // not shrunk: the noise stays as colourful as drawn

        score.layout shouldBe false
        score.backgroundFraction shouldBeLessThan 0.05
        score.meanSaturation shouldBeGreaterThan 0.45
    }

    @Test
    fun `a photo framed by white bars is a photo - nothing white inside the picture`() {
        val framed = sheet(256, 256)
        noise(256, 100).copyTo(framed.submat(Rect(0, 78, 256, 100)))
        val score = LayoutDetector.score(framed)

        score.whiteFraction shouldBeGreaterThan 0.5
        score.contentBackgroundFraction shouldBeLessThan 0.05
        score.layout shouldBe false
    }

    @Test
    fun `a blank sheet is not a layout - nothing is drawn`() {
        LayoutDetector.score(sheet(300, 200)).layout shouldBe false
    }

    @Test
    fun `a file is decoded and shrunk, an undecodable one is null`() {
        val dir = Files.createTempDirectory("layout-detector")
        val png = dir.resolve("plan.png")
        Imgcodecs.imwrite(png.toString(), plan(sheet(2000, 800)))
        val junk = dir.resolve("junk.webp").also { Files.write(it, byteArrayOf(1, 2, 3)) }

        LayoutDetector.score(png.toString())!!.layout shouldBe true
        LayoutDetector.score(junk.toString()) shouldBe null
        LayoutDetector.score(dir.resolve("missing.webp").toString()) shouldBe null
    }
}
