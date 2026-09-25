package hr.workspace.boat4you.common.errorhandling

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * 25.9.2026: bots copy the srcset descriptor into image URLs (`?width=1080 1080w`) and the
 * conversion failure fell through to the generic 500 handler. Unusable or missing request
 * parameters are the caller's mistake → 400, and the raw value must never echo back.
 */
class ApiErrorHandlerParameterTests {
    private val handler = ApiErrorHandler()

    @Test
    fun `a parameter that cannot be converted is a 400 naming the parameter, not the value`() {
        val e = mock(MethodArgumentTypeMismatchException::class.java)
        `when`(e.name).thenReturn("width")
        `when`(e.requiredType).thenReturn(Integer::class.java)
        `when`(e.value).thenReturn("1080 1080w")

        val response = handler.handleMethodArgumentTypeMismatchException(e)

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body!!.code shouldBe ApiErrorCodes.INVALID_REQUEST_PARAMETERS.code
        response.body!!.message shouldContain "width"
        response.body!!.message shouldNotContain "1080w"
    }

    @Test
    fun `a missing required parameter is a 400`() {
        val response = handler.handleMissingServletRequestParameterException(MissingServletRequestParameterException("startDate", "LocalDate"))

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body!!.message shouldContain "startDate"
    }
}
