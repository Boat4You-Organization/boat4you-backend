package hr.workspace.boat4you.domains.calls

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsClient
import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsProperties
import hr.workspace.boat4you.domains.calls.service.AdminCallService
import hr.workspace.boat4you.domains.calls.service.CallAudioStore
import hr.workspace.boat4you.domains.calls.service.CallDispatcher
import hr.workspace.boat4you.domains.calls.service.CallEligibility
import hr.workspace.boat4you.domains.calls.service.CallNotifier
import hr.workspace.boat4you.domains.calls.service.CallOutcomeService
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import hr.workspace.boat4you.domains.calls.service.CallReconciler
import hr.workspace.boat4you.domains.calls.service.CallRetention
import hr.workspace.boat4you.domains.calls.service.CallWebhookProcessor
import hr.workspace.boat4you.domains.calls.service.CallsSettings
import hr.workspace.boat4you.domains.calls.web.AdminCallController
import hr.workspace.boat4you.domains.calls.web.CallWebhookController
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/** Spring builds every call-center bean the way the app will (Kotlin default Clock parameters included). */
@Import(
    CallsSettings::class,
    CallEligibility::class,
    CallPlanner::class,
    CallOutcomeService::class,
    CallNotifier::class,
    CallAudioStore::class,
    CallWebhookProcessor::class,
    CallDispatcher::class,
    CallReconciler::class,
    CallRetention::class,
    AdminCallService::class,
    ElevenLabsProperties::class,
    ElevenLabsClient::class,
    CallWebhookController::class,
    AdminCallController::class,
    CallsWiringTest.Json::class,
)
@TestPropertySource(properties = ["application.upload.directory=build/tmp/calls-wiring"])
class CallsWiringTest : CallsJpaTest() {
    @TestConfiguration
    class Json {
        @Bean
        fun objectMapper() = jacksonObjectMapper()
    }

    @MockitoBean private lateinit var emailService: EmailService

    @Autowired private lateinit var context: ApplicationContext

    @Test
    fun `every call-center bean is created`() {
        listOf(
            CallPlanner::class.java,
            CallDispatcher::class.java,
            CallOutcomeService::class.java,
            CallReconciler::class.java,
            AdminCallService::class.java,
            CallWebhookController::class.java,
            AdminCallController::class.java,
        ).forEach { context.getBean(it).shouldNotBeNull() }
        context.getBean(ElevenLabsProperties::class.java).configured shouldBe false // no keys in tests: nothing can be called
    }
}
