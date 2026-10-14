package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.common.config.RealPriceSwitchesFixture
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentAliases
import hr.workspace.boat4you.domains.catalouge.services.OfferQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtTwinCanonicalService
import hr.workspace.boat4you.domains.external.service.ExternalSyncService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.RequestParam
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.valueParameters
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Re-audit R12 (14.10.2026): `sortBy`, `page` and `size` are part of the yachtSearchListCache key. The controller hands the
 * service a normalised sort (null = the Recommended order for "", "recommended", "recommendedScore" and junk) and a size
 * clamped to 1..100, so junk values share one cache entry with the plain listing; a negative page is a 400, not a 500.
 */
class YachtControllerSortNormalizeTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private data class Call(
        val sortBy: String?,
        val page: Int,
        val size: Int,
    )

    private val yachtQueryingService: YachtQueryingService = mock(YachtQueryingService::class.java)
    private val calls = mutableListOf<Call>()

    private val mvc =
        MockMvcBuilders
            .standaloneSetup(
                YachtController(
                    yachtQueryingService,
                    mock(OfferQueryingService::class.java),
                    mock(ExternalSyncService::class.java),
                    mock(UserRepository::class.java),
                    mock(YachtTwinCanonicalService::class.java),
                    mock(EquipmentAliases::class.java),
                    RealPriceSwitchesFixture.switches(),
                ),
            ).setControllerAdvice(ApiErrorHandler())
            .build()

    @BeforeEach
    fun anonymousSearch() {
        SecurityContextHolder.clearContext()
        `when`(yachtQueryingService.getYachts(any(), any(), any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { inv ->
            calls += Call(inv.getArgument(1), inv.getArgument(3), inv.getArgument(4))
            PageImpl(emptyList<Any>())
        }
    }

    private fun search(query: String): Call {
        calls.clear()
        mvc.get("/public/yachts?$query").andExpect { status { isOk() } }
        assertEquals(1, calls.size, query)
        return calls.single()
    }

    @Test
    fun `junk, empty and recommended sorts reach the service as null - the Recommended order, one cache key`() {
        listOf("size=7", "size=7&sortBy=", "size=7&sortBy=recommended", "size=7&sortBy=recommendedScore", "size=7&sortBy=zz12345")
            .forEach { query -> assertEquals(Call(null, 0, 7), search(query), query) }
    }

    @Test
    fun `a known sort passes unchanged`() {
        listOf("asc", "desc", "lowestPrepayment", "discount", "lengthAsc", "lengthDesc", "id", "idDesc").forEach { sort ->
            assertEquals(sort, search("sortBy=$sort").sortBy)
        }
    }

    @Test
    fun `size is clamped to 1 to 100 before the call`() {
        assertEquals(100, search("size=5000").size)
        assertEquals(100, search("size=101").size)
        assertEquals(100, search("size=100").size)
        assertEquals(1, search("size=0").size)
        assertEquals(1, search("size=-3").size)
        assertEquals(10, search("").size, "default")
    }

    @Test
    fun `ignored params such as q do not change the call`() {
        assertEquals(search("size=7"), search("size=7&q=fresh123&zzz=1"))
    }

    @Test
    fun `a negative page is 400 and never reaches the search`() {
        mvc.get("/public/yachts?page=-1").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { exists() }
        }
        verifyNoInteractions(yachtQueryingService)
    }

    @Test
    fun `getYachts has no q parameter - q is ignored like any unknown param (R12 finding)`() {
        val names =
            YachtController::getYachts.valueParameters.map { p ->
                p.findAnnotation<RequestParam>()?.name?.ifEmpty { null } ?: p.name
            }
        assertTrue("page" in names && "sortBy" in names, "$names")
        assertTrue("q" !in names, "$names")
    }
}
