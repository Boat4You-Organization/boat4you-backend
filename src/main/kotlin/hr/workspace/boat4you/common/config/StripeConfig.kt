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
    }
}
