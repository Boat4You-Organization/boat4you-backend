package hr.workspace.boat4you.domains.catalouge.utils

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Re-run of the LayoutDetector calibration (16.10.2026) on stored images: LAYOUT_CALIBRATION_DIR=<dir> with pos/ (layout
 * drawings) and neg/ (photos), files as served by /public/image/{id}. Prints every measure and the confusion matrix;
 * skipped without the variable (the suite never needs the images).
 *
 *     LAYOUT_CALIBRATION_DIR=/path/to/calib ./gradlew test --tests '*LayoutDetectorCalibrationTest*' -i
 */
class LayoutDetectorCalibrationTest {
    @Test
    fun calibration() {
        val dir = System.getenv("LAYOUT_CALIBRATION_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory, "LAYOUT_CALIBRATION_DIR not set")
        val counts = mutableMapOf<String, Int>()
        listOf("pos" to true, "neg" to false).forEach { (sub, expected) ->
            File(dir, sub).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                val s = LayoutDetector.score(file.path)
                val verdict = verdict(s, expected)
                counts.merge(verdict, 1, Int::plus)
                println("$sub\t${file.name}\t$verdict\t${s?.let(::measures) ?: ""}")
            }
        }
        val tp = counts["TP"] ?: 0
        val fp = counts["FP"] ?: 0
        val fn = counts["FN"] ?: 0
        val precision = if (tp + fp == 0) "-" else "%.3f".format(Locale.ROOT, tp.toDouble() / (tp + fp))
        val recall = if (tp + fn == 0) "-" else "%.3f".format(Locale.ROOT, tp.toDouble() / (tp + fn))
        println("CALIBRATION $counts precision=$precision recall=$recall")
    }

    private fun verdict(
        s: LayoutScore?,
        expected: Boolean,
    ): String =
        when {
            s == null -> "unreadable"
            s.layout && expected -> "TP"
            s.layout -> "FP"
            expected -> "FN"
            else -> "TN"
        }

    private fun measures(s: LayoutScore): String =
        String.format(
            Locale.ROOT,
            "white=%.2f\tbg=%.2f\tcontentBg=%.2f\tsat=%.2f\tedge=%.3f\tdark=%d\tscore=%.2f\t%dx%d",
            s.whiteFraction,
            s.backgroundFraction,
            s.contentBackgroundFraction,
            s.meanSaturation,
            s.edgeDensity,
            s.darkComponents,
            s.score,
            s.width,
            s.height,
        )
}
