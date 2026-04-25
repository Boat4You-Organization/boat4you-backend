package hr.workspace.boat4you.common.config

import com.stripe.Stripe
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

@Configuration
class StripeConfig(
    @Value("\${application.stripe.secret-key}") private val secretKey: String,
) {
    @PostConstruct
    fun init() {
        Stripe.apiKey = secretKey
        // Global timeouts on the Stripe SDK's default HTTP client. Without
        // these, a hung Stripe API call (rare but happens under their own
        // degraded availability) blocks the request thread indefinitely —
        // customer sees an eternal spinner on /payment.
        Stripe.setConnectTimeout(5_000) // ms
        Stripe.setReadTimeout(15_000)
        Stripe.setMaxNetworkRetries(2)  // Stripe retries 5xx + network errors
    }
}
