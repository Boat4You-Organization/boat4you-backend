package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.service.PrProperties
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource

/** The `pr:` block of application.yml binds to PrProperties, with the plan's defaults and every env override. */
class PrPropertiesTest {
    @Configuration
    @EnableConfigurationProperties(PrProperties::class)
    internal class Config

    private fun runner(vararg env: Pair<String, String>): ApplicationContextRunner {
        val yml = YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml")).single()
        return ApplicationContextRunner()
            .withInitializer { ctx ->
                ctx.environment.propertySources.addLast(yml)
                ctx.environment.propertySources.addFirst(
                    org.springframework.core.env.MapPropertySource("env", env.toMap()),
                )
            }.withUserConfiguration(Config::class.java)
    }

    @Test
    fun `application yml defaults equal the class defaults`() {
        runner().run { ctx ->
            val p = ctx.getBean(PrProperties::class.java)
            p shouldBe PrProperties()
            p.clearedMarkets() shouldBe setOf("US", "GB", "AU", "NZ", "FR", "HR", "GR")
            p.mail.enabled shouldBe false
            p.mail.port shouldBe 465
            p.mail.ssl shouldBe true
            p.mail.from shouldBe "Mario Kuzmanić | Boat4you Group <press@boat4you.com>"
            p.mail.bcc shouldBe "press@boat4you.com"
            p.privacyUrl shouldBe "https://www.boat4you.com/press/privacy"
            p.ourDomains.size shouldBe 7
            ctx.environment.getProperty("pr.retention.cron") shouldBe "0 30 3 1 * *"
        }
    }

    @Test
    fun `every setting comes from its env variable`() {
        runner(
            "PR_MARKETS_CLEARED" to "gb, uk ,DE,xx1",
            "PR_MAIL_ENABLED" to "true",
            "PR_MAIL_HOST" to "mail.example.com",
            "PR_MAIL_PORT" to "587",
            "PR_MAIL_SSL" to "false",
            "PR_MAIL_USERNAME" to "press@example.com",
            "PR_MAIL_PASSWORD" to "secret",
            "PR_PRIVACY_URL" to "https://example.com/privacy",
            "PR_API_KEY" to "k1",
            "PR_OPR_API_KEY" to "k2",
        ).run { ctx ->
            val p = ctx.getBean(PrProperties::class.java)
            p.clearedMarkets() shouldBe setOf("GB", "DE")
            p.mail.enabled shouldBe true
            p.mail.host shouldBe "mail.example.com"
            p.mail.port shouldBe 587
            p.mail.ssl shouldBe false
            p.mail.username shouldBe "press@example.com"
            p.mail.password shouldBe "secret"
            p.privacyUrl shouldBe "https://example.com/privacy"
            p.apiKey shouldBe "k1"
            p.oprApiKey shouldBe "k2"
        }
    }
}
