package hr.workspace.boat4you.domains.chat.service

import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Golden files under `src/test/resources`: `check` compares, and with `GOLDEN_UPDATE=1` in the environment writes the
 * file instead. Generate a golden file once, on unchanged code, and review the diff before it is committed.
 */
object GoldenFile {
    fun read(path: String): String = File("src/test/resources/$path").readText()

    fun check(
        path: String,
        actual: String,
    ) {
        val file = File("src/test/resources/$path")
        if (System.getenv("GOLDEN_UPDATE") == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        kotlin.check(file.exists()) { "golden $path missing - run once with GOLDEN_UPDATE=1 on unchanged code" } // not this object's check(path, actual)
        actual shouldBe file.readText()
    }
}
