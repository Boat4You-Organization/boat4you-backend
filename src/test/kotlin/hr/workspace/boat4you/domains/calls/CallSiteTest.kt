package hr.workspace.boat4you.domains.calls

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CallSiteTest {
    @Test
    fun `a known key resolves to the spoken site name`() {
        CallSite.fromKey("italy")!!.spokenName shouldBe "Catamaran Charter Italy"
        CallSite.fromKey("boat4you")!!.domain shouldBe "www.boat4you.com"
        CallSite.fromKey(" CY ")!!.spokenName shouldBe "Yacht Charter Croatia"
    }

    @Test
    fun `an unknown or missing key is null`() {
        CallSite.fromKey("unknown").shouldBeNull()
        CallSite.fromKey(null).shouldBeNull()
    }

    @Test
    fun `the seven keys are unique`() {
        CallSite.entries.map { it.key }.toSet().size shouldBe 7
    }
}
