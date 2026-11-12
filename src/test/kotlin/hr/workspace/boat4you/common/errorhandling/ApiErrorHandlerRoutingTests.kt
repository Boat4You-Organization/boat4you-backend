package hr.workspace.boat4you.common.errorhandling

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 12.11.2026: a request no controller serves (unknown path, wrong method, wrong content type) fell through to the
 * generic handler and came back as a 500 "Unknown error", which filled the log with server errors that were not ours.
 * They are the caller's mistake and keep the status Spring gives them.
 */
class ApiErrorHandlerRoutingTests {
    @RestController
    class Probe {
        @GetMapping("/public/probe")
        fun probe(): String = "ok"

        @PostMapping("/public/probe/json", consumes = [MediaType.APPLICATION_JSON_VALUE])
        fun json(
            @RequestBody body: Map<String, Any>,
        ): String = "ok ${body.size}"

        @GetMapping("/public/probe/broken")
        fun broken(): String = error("a real fault of ours")
    }

    private val mvc = MockMvcBuilders.standaloneSetup(Probe()).setControllerAdvice(ApiErrorHandler()).build()

    @Test
    fun `a path nobody serves is a 404`() {
        mvc
            .perform(get("/public/no-such-route"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(ApiErrorCodes.RESOURCE_NOT_FOUND.code))
    }

    @Test
    fun `a method the path does not take is a 405 that names the allowed one`() {
        mvc
            .perform(delete("/public/probe"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string("Allow", "GET"))
            .andExpect(jsonPath("$.code").value(ApiErrorCodes.REQUEST_NOT_SUPPORTED.code))
    }

    @Test
    fun `a content type the path does not read is a 415`() {
        mvc
            .perform(post("/public/probe/json").contentType(MediaType.TEXT_PLAIN).content("x"))
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value(ApiErrorCodes.REQUEST_NOT_SUPPORTED.code))
    }

    @Test
    fun `a fault of ours is still the generic 500`() {
        mvc
            .perform(get("/public/probe/broken"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value(ApiErrorCodes.GENERAL_ERROR.code))
    }
}
