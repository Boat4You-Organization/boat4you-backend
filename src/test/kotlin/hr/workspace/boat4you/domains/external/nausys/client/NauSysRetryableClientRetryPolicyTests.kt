package hr.workspace.boat4you.domains.external.nausys.client

import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import org.openapitools.client.nausys.model.RestFreeYachtsRequest
import org.openapitools.client.nausys.model.RestFreeYachtsSearchRequest
import org.openapitools.client.nausys.model.RestYachtReservationsRequest
import org.springframework.retry.annotation.Retryable
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the single-retry-layer invariant: once the RestClient interceptor
 * gives up on 429 (NauSysRateLimitedException) the outer @Retryable must NOT
 * re-enter (was 3 × 6 = 18 partner hits per logical call).
 */
class NauSysRetryableClientRetryPolicyTests {
    private fun retryable(
        name: String,
        vararg params: Class<*>,
    ): Retryable {
        val annotation = NauSysRetryableClient::class.java.getMethod(name, *params).getAnnotation(Retryable::class.java)
        assertNotNull(annotation, "$name must stay @Retryable")
        return annotation
    }

    private fun excludesRateLimited(annotation: Retryable) =
        annotation.noRetryFor.any { it.java.isAssignableFrom(NauSysRateLimitedException::class.java) }

    @Test
    fun `getFreeYachts does not retry an exhausted 429`() {
        assertTrue(excludesRateLimited(retryable("getFreeYachts", RestFreeYachtsRequest::class.java)))
    }

    @Test
    fun `getFreeYachtsSearchForAsync does not retry an exhausted 429`() {
        assertTrue(excludesRateLimited(retryable("getFreeYachtsSearchForAsync", RestFreeYachtsSearchRequest::class.java)))
    }

    @Test
    fun `getReservation inherits the exclusion through ExternalSystemException`() {
        assertTrue(excludesRateLimited(retryable("getReservation", RestYachtReservationsRequest::class.java)))
    }
}
