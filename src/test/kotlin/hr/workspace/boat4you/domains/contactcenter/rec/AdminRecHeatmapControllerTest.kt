package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** The heatmap endpoints (P6 R3): a known site, page type and device, a range of at most 400 days (30 by default). */
class AdminRecHeatmapControllerTest {
    private val calls = mutableListOf<List<Any?>>()
    private val heatmaps =
        mock(RecHeatmapQuery::class.java) { inv ->
            calls += inv.arguments.toList()
            when (inv.method.name) {
                "heatmap" -> Heatmap("boat4you", "BOAT", "MOBILE", 3, 1, 8, emptyList(), emptyMap(), emptyList(), 20, 7L)
                "groups" -> listOf(HeatmapGroup("BOAT", 3, 8))
                "insights" -> listOf(RecInsight("DEAD", "BOAT", "photo", "", "img", 4, 100, 3))
                else -> null
            }
        }
    private val mvc by lazy {
        MockMvcBuilders
            .standaloneSetup(AdminRecController(mock(RecSettingsStore::class.java), mock(RecQuery::class.java), RecFiles("/nonexistent"), heatmaps))
            .setControllerAdvice(ApiErrorHandler())
            .build()
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun admin() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("mario@example.com", null, listOf(SimpleGrantedAuthority("SYSTEM_ADMIN")))
    }

    @Test
    fun `a heatmap, its groups and the insights of a site, 30 days by default`() {
        admin()
        mvc.get("/admin/cc/rec/heatmap?siteId=boat4you&pageType=BOAT&device=MOBILE").andExpect {
            status { isOk() }
            jsonPath("$.background") { value(7) }
        }
        calls.last().take(3) shouldBe listOf("boat4you", "BOAT", "MOBILE")
        val from = calls.last()[3] as java.time.Instant
        val to = calls.last()[4] as java.time.Instant
        (to.epochSecond - from.epochSecond) shouldBe 30L * 24 * 3600
        mvc.get("/admin/cc/rec/heatmap/groups?siteId=boat4you&device=DESKTOP").andExpect {
            status { isOk() }
            jsonPath("$[0].pageType") { value("BOAT") }
        }
        mvc.get("/admin/cc/rec/insights?siteId=boat4you&device=DESKTOP").andExpect {
            status { isOk() }
            jsonPath("$[0].kind") { value("DEAD") }
        }
    }

    @Test
    fun `a wrong site, page type, device or range is 400 and nothing is queried`() {
        admin()
        mvc.get("/admin/cc/rec/heatmap?siteId=evil&pageType=BOAT&device=DESKTOP").andExpect { status { isBadRequest() } }
        mvc.get("/admin/cc/rec/heatmap?siteId=boat4you&pageType=X&device=DESKTOP").andExpect { status { isBadRequest() } }
        mvc.get("/admin/cc/rec/heatmap?siteId=boat4you&pageType=BOAT&device=WATCH").andExpect { status { isBadRequest() } }
        mvc.get("/admin/cc/rec/heatmap?siteId=boat4you&pageType=BOAT").andExpect { status { isBadRequest() } }
        mvc.get("/admin/cc/rec/insights?siteId=boat4you&device=DESKTOP&from=2026-11-03T00:00:00Z&to=2026-11-01T00:00:00Z").andExpect { status { isBadRequest() } }
        mvc.get("/admin/cc/rec/heatmap/groups?device=DESKTOP").andExpect { status { isBadRequest() } }
        calls.size shouldBe 0
    }
}
