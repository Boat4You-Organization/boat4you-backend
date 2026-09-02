package hr.workspace.boat4you.domains.external.nausys.config

import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import java.net.URI
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TooManyRequestsRetryInterceptorTests {
    private val backoff = longArrayOf(1_000, 2_000, 5_000, 10_000, 20_000)
    private val request = MockClientHttpRequest(HttpMethod.POST, URI.create("http://ws.nausys.com/CBMS-external/rest/yachtReservation/v6/freeYachts"))

    private fun response(
        status: Int,
        retryAfter: String? = null,
    ): ClientHttpResponse =
        MockClientHttpResponse(ByteArray(0), HttpStatusCode.valueOf(status)).apply {
            retryAfter?.let { headers.set(HttpHeaders.RETRY_AFTER, it) }
        }

    private class Harness(
        responses: List<ClientHttpResponse>,
        backoff: LongArray,
    ) {
        val stats = NauSysRateLimitStats()
        val sleeps = mutableListOf<Long>()
        var requests = 0
        private val queue = ArrayDeque(responses)
        val execution = ClientHttpRequestExecution { _, _ ->
            requests++
            queue.removeFirst()
        }
        val interceptor = TooManyRequestsRetryInterceptor(stats, backoff) { sleeps += it }
    }

    @Test
    fun `429 429 200 replays twice with ladder backoff plus jitter`() {
        val h = Harness(listOf(response(429), response(429), response(200)), backoff)
        val result = h.interceptor.intercept(request, ByteArray(0), h.execution)
        assertEquals(200, result.statusCode.value())
        assertEquals(3, h.requests)
        assertEquals(2, h.sleeps.size)
        assertTrue(h.sleeps[0] in 1_000..1_999, "first sleep ${h.sleeps[0]}")
        assertTrue(h.sleeps[1] in 2_000..2_999, "second sleep ${h.sleeps[1]}")
        assertEquals(2, h.stats.tooManyRequests.get())
        assertEquals(0, h.stats.exhausted.get())
    }

    @Test
    fun `Retry-After delta seconds wins over the ladder`() {
        val h = Harness(listOf(response(429, "7"), response(200)), backoff)
        h.interceptor.intercept(request, ByteArray(0), h.execution)
        assertEquals(1, h.sleeps.size)
        assertTrue(h.sleeps[0] in 7_000..7_999, "sleep ${h.sleeps[0]}")
    }

    @Test
    fun `budget exhausted throws NauSysRateLimitedException after exactly ladder plus one requests`() {
        val h = Harness(List(6) { response(429) }, backoff)
        val ex = assertFailsWith<NauSysRateLimitedException> { h.interceptor.intercept(request, ByteArray(0), h.execution) }
        assertEquals(6, h.requests)
        assertEquals(6, ex.attempts)
        assertEquals("/CBMS-external/rest/yachtReservation/v6/freeYachts", ex.path)
        assertEquals(5, h.sleeps.size)
        assertEquals(6, h.stats.tooManyRequests.get())
        assertEquals(1, h.stats.exhausted.get())
    }

    @Test
    fun `non-429 responses are returned untouched without sleeping`() {
        val h = Harness(listOf(response(502)), backoff)
        val result = h.interceptor.intercept(request, ByteArray(0), h.execution)
        assertEquals(502, result.statusCode.value())
        assertEquals(1, h.requests)
        assertTrue(h.sleeps.isEmpty())
        assertEquals(0, h.stats.tooManyRequests.get())
    }

    @Test
    fun `retryAfterMs parses seconds, RFC-1123 dates, caps at 60 s and ignores garbage`() {
        assertEquals(0, TooManyRequestsRetryInterceptor.retryAfterMs(response(429)))
        assertEquals(3_000, TooManyRequestsRetryInterceptor.retryAfterMs(response(429, "3")))
        assertEquals(60_000, TooManyRequestsRetryInterceptor.retryAfterMs(response(429, "999")))
        assertEquals(0, TooManyRequestsRetryInterceptor.retryAfterMs(response(429, "soon")))
        val inThirty = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        val ms = TooManyRequestsRetryInterceptor.retryAfterMs(response(429, inThirty))
        assertTrue(ms in 20_000..30_000, "date-based Retry-After $ms")
        val past = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(0, TooManyRequestsRetryInterceptor.retryAfterMs(response(429, past)))
    }
}
