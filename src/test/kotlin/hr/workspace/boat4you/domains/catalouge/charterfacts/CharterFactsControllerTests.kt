package hr.workspace.boat4you.domains.catalouge.charterfacts

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class CharterFactsControllerTests {
    private val readService: CharterFactsReadService = mock(CharterFactsReadService::class.java)
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(CharterFactsController(readService))
            .setControllerAdvice(ApiErrorHandler())
            .build()

    @Test
    fun `facts row - 200 with the payload and an hour of public caching`() {
        val node = ObjectMapper().createObjectNode().put("did", "c-54").put("activeBoats", 812)
        `when`(readService.find("c-54", VesselType.CATAMARAN)).thenReturn(node)

        mvc.get("/public/charter-facts?did=c-54&vesselType=CATAMARAN").andExpect {
            status { isOk() }
            header { string("Cache-Control", "max-age=3600, public") }
            jsonPath("$.activeBoats") { value(812) }
        }
    }

    @Test
    fun `no row - 404`() {
        mvc.get("/public/charter-facts?did=r-5").andExpect { status { isNotFound() } }
    }

    @Test
    fun `a dual-source region pair reads its one row, in either order`() {
        val node = ObjectMapper().createObjectNode().put("did", "r-187,r-19").put("activeBoats", 1364)
        `when`(readService.find("r-187,r-19", null)).thenReturn(node)

        listOf("r-187,r-19", "r-19,r-187").forEach { did ->
            mvc.get("/public/charter-facts") { param("did", did) }.andExpect {
                status { isOk() }
                jsonPath("$.activeBoats") { value(1364) }
            }
        }
        // two did params arrive joined the same way
        mvc.get("/public/charter-facts") { param("did", "r-19", "r-187") }.andExpect { status { isOk() } }
    }

    @Test
    fun `malformed did - 400 before any database read`() {
        listOf("l-l-19", "c-54,r-5", "r-5,r-5", "r-1,r-2,r-3", "l-1,l-2", "x-1", "c-", "c-99999999999999").forEach { did ->
            mvc.get("/public/charter-facts") { param("did", did) }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(1102) }
            }
        }
        verifyNoInteractions(readService)
    }

    @Test
    fun `missing did or unknown vesselType - 400`() {
        mvc.get("/public/charter-facts").andExpect { status { isBadRequest() } }
        mvc.get("/public/charter-facts?did=c-54&vesselType=SPACESHIP").andExpect { status { isBadRequest() } }
    }
}
