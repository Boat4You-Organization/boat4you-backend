package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppressionRepository
import hr.workspace.boat4you.domains.pr.PrJpaTest.TestClock
import hr.workspace.boat4you.domains.pr.jpa.PrAlertFeedRepository
import hr.workspace.boat4you.domains.pr.jpa.PrContactRepository
import hr.workspace.boat4you.domains.pr.jpa.PrMentionRepository
import hr.workspace.boat4you.domains.pr.jpa.PrOutletRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitchRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPlacementRepository
import hr.workspace.boat4you.domains.pr.jpa.PrStudyRepository
import hr.workspace.boat4you.domains.pr.links.PrLinkCheckService
import hr.workspace.boat4you.domains.pr.links.PrPageFetcher
import hr.workspace.boat4you.domains.pr.mentions.PrMentionService
import hr.workspace.boat4you.domains.pr.service.OpenPageRankClient
import hr.workspace.boat4you.domains.pr.service.PrContactService
import hr.workspace.boat4you.domains.pr.service.PrMailSender
import hr.workspace.boat4you.domains.pr.service.PrOverviewService
import hr.workspace.boat4you.domains.pr.service.PrPitchService
import hr.workspace.boat4you.domains.pr.service.PrPlacementService
import hr.workspace.boat4you.domains.pr.service.PrProperties
import hr.workspace.boat4you.domains.pr.service.PrStudyService
import hr.workspace.boat4you.domains.pr.web.AdminPrController
import hr.workspace.boat4you.domains.pr.web.PrApiController
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.stereotype.Component
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

/**
 * R30: Spring builds every PR bean with its real constructor. The shared PR context (PrJpaTest) holds them all, with the
 * real application.yml binding; a second, throw-away context without a Clock bean shows the Kotlin defaults that prod
 * relies on (`clock = Clock.systemUTC()`, PrMailSender's `senderFactory = ::defaultSender`) are what Spring picks.
 */
class PrContextWiringTest : PrJpaTest() {
    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var testClock: TestClock

    /** Every Spring bean class of the PR module outside `web`. */
    private val prBeans =
        listOf(
            PrContactService::class.java,
            PrStudyService::class.java,
            PrMailSender::class.java,
            PrPitchService::class.java,
            PrPlacementService::class.java,
            PrLinkCheckService::class.java,
            PrMentionService::class.java,
            PrOverviewService::class.java,
            OpenPageRankClient::class.java,
            PrPageFetcher::class.java,
        )

    /** The stand-ins PrJpaTest defines (no network in tests); everything else is the real class. */
    private val mocked = setOf(PrPageFetcher::class.java, OpenPageRankClient::class.java)

    private val clockServices =
        listOf(PrPitchService::class.java, PrLinkCheckService::class.java, PrMentionService::class.java, PrOverviewService::class.java)

    private fun field(
        bean: Any,
        name: String,
    ): Any? = ReflectionTestUtils.getField(AopTestUtils.getUltimateTargetObject<Any>(bean), name)

    @Suppress("UNCHECKED_CAST")
    private fun senderFactory(sender: PrMailSender) = field(sender, "senderFactory") as (PrProperties.Mail) -> JavaMailSender

    @Test
    fun beanListCoversEveryPrComponentOutsideWeb() {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply { addIncludeFilter(AnnotationTypeFilter(Component::class.java)) }
        val found =
            scanner
                .findCandidateComponents("hr.workspace.boat4you.domains.pr")
                .map { it.beanClassName!! }
                // Main code lives in sub-packages only; classes directly in `pr` are these tests' own configs.
                .filter { it.removePrefix("hr.workspace.boat4you.domains.pr.").contains('.') }
                .filterNot { it.startsWith("hr.workspace.boat4you.domains.pr.web.") }

        found shouldContainExactlyInAnyOrder prBeans.map { it.name }
    }

    @Test
    fun sharedContextHoldsEveryPrBeanOnce() {
        prBeans.forEach { type ->
            val beans = context.getBeansOfType(type).values.toList()
            beans shouldHaveSize 1
            mockingDetails(beans.single()).isMock shouldBe (type in mocked)
        }
        context.getBeansOfType(AdminPrController::class.java).size shouldBe 0
        context.getBeansOfType(PrApiController::class.java).size shouldBe 0
    }

    @Test
    fun sharedContextBindsApplicationYmlAndInjectsTestClock() {
        val props = context.getBean(PrProperties::class.java)
        props.mail.enabled shouldBe false
        props.mail.port shouldBe 465
        props.mail.ssl shouldBe true
        props.mail.from shouldBe "Mario Kuzmanić | Boat4you Group <press@boat4you.com>"
        props.mail.bcc shouldBe "press@boat4you.com"
        props.privacyUrl shouldBe "https://www.boat4you.com/press/privacy"
        props.clearedMarkets() shouldBe setOf("US", "GB", "AU", "NZ", "FR", "HR", "GR")
        props.ourDomains shouldBe PrProperties.OUR_DOMAINS

        clockServices.forEach { type -> field(context.getBean(type), "clock") shouldBeSameInstanceAs testClock }
        // PrMailSender with the real default factory (never used here: the kill switch is off).
        senderFactory(context.getBean(PrMailSender::class.java))(PrProperties.Mail(host = "mail.example.com"))
            .shouldBeInstanceOf<JavaMailSenderImpl>()
            .host shouldBe "mail.example.com"
    }

    @Test
    fun withoutClockBeanSpringUsesKotlinDefaults() {
        val dependencies =
            listOf(
                PrOutletRepository::class.java,
                PrContactRepository::class.java,
                PrStudyRepository::class.java,
                PrPitchRepository::class.java,
                PrPlacementRepository::class.java,
                PrAlertFeedRepository::class.java,
                PrMentionRepository::class.java,
                EmailSuppressionRepository::class.java,
                JdbcTemplate::class.java,
                PlatformTransactionManager::class.java,
                PrProperties::class.java,
                BrandRegistry::class.java,
            )
        AnnotationConfigApplicationContext().use { plain ->
            dependencies.forEach { plain.beanFactory.registerSingleton(it.name, context.getBean(it)) }
            // The real classes, built by Spring's annotation-driven constructor autowiring as in the app; no Clock, no mocks.
            plain.register(*prBeans.toTypedArray())
            plain.refresh()

            clockServices.forEach { type -> field(plain.getBean(type), "clock") shouldBe Clock.systemUTC() }
            val sender = plain.getBean(PrMailSender::class.java)
            senderFactory(sender)(PrProperties.Mail(host = "mail.example.com", port = 587, ssl = false))
                .shouldBeInstanceOf<JavaMailSenderImpl>()
                .port shouldBe 587
            field(plain.getBean(PrPitchService::class.java), "mail") shouldBeSameInstanceAs sender
            prBeans.forEach { mockingDetails(plain.getBean(it)).isMock shouldBe false }
        }
    }
}
