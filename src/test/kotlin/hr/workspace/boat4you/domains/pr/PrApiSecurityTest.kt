package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.calls.CallsTestJson
import hr.workspace.boat4you.domains.pr.dto.ImportResult
import hr.workspace.boat4you.domains.pr.dto.PrPitchDraftRequest
import hr.workspace.boat4you.domains.pr.dto.PrPitchDto
import hr.workspace.boat4you.domains.pr.dto.PrPlacementDto
import hr.workspace.boat4you.domains.pr.dto.PrPlacementRequest
import hr.workspace.boat4you.domains.pr.dto.PrStudyDto
import hr.workspace.boat4you.domains.pr.dto.PrStudyRequest
import hr.workspace.boat4you.domains.pr.jpa.PrLinkState
import hr.workspace.boat4you.domains.pr.jpa.PrPitchStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchTemplate
import hr.workspace.boat4you.domains.pr.jpa.PrStudyStatus
import hr.workspace.boat4you.domains.pr.service.PrContactService
import hr.workspace.boat4you.domains.pr.service.PrFooter
import hr.workspace.boat4you.domains.pr.service.PrPitchService
import hr.workspace.boat4you.domains.pr.service.PrPlacementService
import hr.workspace.boat4you.domains.pr.service.PrProperties
import hr.workspace.boat4you.domains.pr.service.PrStudyService
import hr.workspace.boat4you.domains.pr.web.PrApiController
import hr.workspace.boat4you.domains.pr.web.PrErrorAdvice
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.RequestContextFactory
import hr.workspace.boat4you.security.JwtAuthenticationFilter
import hr.workspace.boat4you.security.config.SecurityConfiguration
import hr.workspace.boat4you.security.jpa.TokenRepository
import hr.workspace.boat4you.security.services.JwtService
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import jakarta.servlet.Filter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.time.Instant

/**
 * The PR API for Claude sessions behind the production security chain (SecurityConfiguration with its real JWT filter):
 * every /api/pr/v1 route is permitAll and every call checks X-Api-Key against pr.api-key itself. It writes drafts only: there is
 * no send route.
 */
@SpringJUnitWebConfig(PrApiSecurityTest.Config::class)
class PrApiSecurityTest {
    @Configuration
    @EnableWebMvc
    @Import(SecurityConfiguration::class)
    internal class Config : WebMvcConfigurer {
        override fun configureMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters += CallsTestJson.converters()
        }

        @Bean
        fun jwtAuthenticationFilter() =
            JwtAuthenticationFilter(
                mock(UserRepository::class.java),
                mock(TokenRepository::class.java),
                mock(RequestContextFactory::class.java),
                mock(JwtService::class.java),
            )

        @Bean
        fun prProperties() = PrProperties(apiKey = KEY)

        @Bean
        fun prPitchService(): PrPitchService = mock(PrPitchService::class.java)

        @Bean
        fun prContactService(): PrContactService = mock(PrContactService::class.java)

        @Bean
        fun prStudyService(): PrStudyService = mock(PrStudyService::class.java)

        @Bean
        fun prPlacementService(): PrPlacementService = mock(PrPlacementService::class.java)

        @Bean
        fun prApiController(
            props: PrProperties,
            pitches: PrPitchService,
            contacts: PrContactService,
            studies: PrStudyService,
            placements: PrPlacementService,
        ) = PrApiController(props, pitches, contacts, studies, placements)

        @Bean
        fun prErrorAdvice() = PrErrorAdvice()

        @Bean
        fun apiErrorHandler() = ApiErrorHandler()
    }

    companion object {
        const val KEY = "test-pr-key-0123456789"
    }

    @Autowired private lateinit var context: WebApplicationContext

    @Autowired private lateinit var pitches: PrPitchService

    @Autowired private lateinit var contacts: PrContactService

    @Autowired private lateinit var studies: PrStudyService

    @Autowired private lateinit var placements: PrPlacementService

    private val mvc: MockMvc by lazy {
        MockMvcBuilders
            .webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain", Filter::class.java))
            .build()
    }

    @BeforeEach
    fun resetMocks() = reset(pitches, contacts, studies, placements)

    private val draft = PrPitchDraftRequest(studyId = 3, contactId = 7, template = PrPitchTemplate.DATA_PITCH, subject = "S", body = "B")

    private val draftJson = """{"studyId":3,"contactId":7,"template":"DATA_PITCH","subject":"S","body":"B"}"""

    private val placement = PrPlacementRequest(studyId = 3, articleUrl = "https://www.example-news.co.uk/travel/a", anchorText = "study")

    private val placementJson = """{"studyId":3,"articleUrl":"https://www.example-news.co.uk/travel/a","anchorText":"study"}"""

    private fun json(
        body: String,
        key: String?,
    ): MockHttpServletRequestDsl.() -> Unit =
        {
            contentType = MediaType.APPLICATION_JSON
            content = body
            key?.let { header("X-Api-Key", it) }
        }

    private fun every(key: String?): List<ResultActionsDsl> =
        listOf(
            mvc.post("/api/pr/v1/pitches", dsl = json(draftJson, key)),
            mvc.post("/api/pr/v1/contacts/import") {
                contentType = MediaType.parseMediaType("text/csv")
                content = "market,outlet,tier,email\n"
                key?.let { header("X-Api-Key", it) }
            },
            mvc.post("/api/pr/v1/studies", dsl = json("""{"number":1,"siteId":"boat4you","title":"T"}""", key)),
            mvc.get("/api/pr/v1/pitches?studyId=3") { key?.let { header("X-Api-Key", it) } },
            mvc.post("/api/pr/v1/placements", dsl = json(placementJson, key)),
        )

    private fun pitchDto() =
        PrPitchDto(
            id = 11,
            studyId = 3,
            contactId = 7,
            contactEmail = "jane@example.co.uk",
            contactName = "Jane",
            outletName = "The Daily Example",
            parentPitchId = null,
            template = PrPitchTemplate.DATA_PITCH,
            subject = "S",
            body = "B",
            status = PrPitchStatus.DRAFT,
            decision = "ALLOWED",
            footer = PrFooter.text("https://www.boat4you.com/press/privacy"),
            messageId = null,
            sentAt = null,
            error = null,
            replyNote = null,
            createdAt = Instant.parse("2026-11-05T10:00:00Z"),
            updatedAt = Instant.parse("2026-11-05T10:00:00Z"),
        )

    private fun placementDto() =
        PrPlacementDto(
            id = 21,
            studyId = 3,
            outletId = null,
            outletName = null,
            pitchId = null,
            articleUrl = "https://www.example-news.co.uk/travel/a",
            publishedOn = null,
            targetUrl = null,
            anchorText = "study",
            linkState = PrLinkState.NOT_CHECKED,
            lastGoodState = null,
            lastHttpStatus = null,
            lastCheckedAt = null,
            alert = false,
            alertReason = null,
            domainRank = null,
            createdAt = Instant.parse("2026-11-05T10:00:00Z"),
        )

    @Test
    fun apiRejectsMissingOrWrongKey() {
        listOf(null, "", "wrong", KEY.dropLast(1), "$KEY ", KEY.uppercase()).forEach { key ->
            every(key).forEach {
                it.andExpect {
                    status { isUnauthorized() }
                    jsonPath("$.code") { value("UNAUTHORIZED") }
                    jsonPath("$.message") { exists() }
                }
            }
        }
        verifyNoInteractions(pitches, contacts, studies, placements)
    }

    @Test
    fun blankConfiguredKeyClosesTheApi() {
        val closed =
            MockMvcBuilders
                .standaloneSetup(PrApiController(PrProperties(apiKey = " "), pitches, contacts, studies, placements))
                .setControllerAdvice(PrErrorAdvice())
                .setMessageConverters(*CallsTestJson.converters())
                .build()

        listOf(null, "", " ", "anything").forEach { key ->
            closed.post("/api/pr/v1/pitches", dsl = json(draftJson, key)).andExpect { status { isUnauthorized() } }
            closed.get("/api/pr/v1/pitches") { key?.let { header("X-Api-Key", it) } }.andExpect { status { isUnauthorized() } }
            closed.post("/api/pr/v1/placements", dsl = json(placementJson, key)).andExpect { status { isUnauthorized() } }
        }
        verifyNoInteractions(pitches, contacts, studies, placements)
    }

    @Test
    fun validKeyWritesADraftWithoutCsrfToken() {
        `when`(pitches.createDraft(draft)).thenReturn(pitchDto())

        mvc.post("/api/pr/v1/pitches", dsl = json(draftJson, KEY)).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(11) }
            jsonPath("$.status") { value("DRAFT") }
            jsonPath("$.decision") { value("ALLOWED") }
            jsonPath("$.footer") { value(PrFooter.text("https://www.boat4you.com/press/privacy")) }
        }
        verify(pitches).createDraft(draft)
    }

    @Test
    fun validKeyReachesEveryRoute() {
        `when`(contacts.importCsv("market,outlet,tier,email\n")).thenReturn(ImportResult(0, 0, emptyList()))
        `when`(studies.create(PrStudyRequest(number = 1, siteId = "boat4you", title = "T")))
            .thenReturn(PrStudyDto(1, 1, "boat4you", "T", null, PrStudyStatus.DRAFT, null, null, Instant.parse("2026-11-05T10:00:00Z")))
        `when`(pitches.list(3, null)).thenReturn(listOf(pitchDto()))
        `when`(pitches.createDraft(draft)).thenReturn(pitchDto())
        `when`(placements.create(placement)).thenReturn(placementDto())

        every(KEY).forEach { it.andExpect { status { isOk() } } }

        verify(contacts).importCsv("market,outlet,tier,email\n")
        verify(studies).create(PrStudyRequest(number = 1, siteId = "boat4you", title = "T"))
        verify(pitches).list(3, null)
        verify(placements).create(placement)
        mvc.post("/api/pr/v1/placements", dsl = json(placementJson, KEY)).andExpect {
            jsonPath("$.id") { value(21) }
            jsonPath("$.linkState") { value("NOT_CHECKED") }
        }
        mvc.get("/api/pr/v1/pitches?studyId=3") { header("X-Api-Key", KEY) }.andExpect { jsonPath("$[0].id") { value(11) } }
    }

    @Test
    fun apiHasNoSendEndpoint() {
        listOf("/api/pr/v1/pitches/1/send", "/api/pr/v1/send", "/api/pr/v1/pitches/send").forEach { path ->
            mvc.post(path) { header("X-Api-Key", KEY) }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("NOT_FOUND") }
            }
        }

        val routes =
            context
                .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
                .handlerMethods
                .filter { (_, method) -> method.beanType == PrApiController::class.java }
                .flatMap { (info, _) -> info.patternValues.map { "${info.methodsCondition.methods.joinToString().ifEmpty { "ANY" }} $it" } }
        // Every route the API has; the catch-all only answers 404.
        routes shouldContainExactlyInAnyOrder
            listOf(
                "POST /api/pr/v1/pitches",
                "GET /api/pr/v1/pitches",
                "POST /api/pr/v1/contacts/import",
                "POST /api/pr/v1/studies",
                "POST /api/pr/v1/placements",
                "ANY /api/pr/v1/**",
            )
        verifyNoInteractions(pitches, contacts, studies, placements)
    }

    @Test
    fun theChainStillGuardsEverythingElse() {
        // Proves the filter chain is really in front of the API: a non-public path is refused before any controller.
        mvc.get("/admin/pr/pitches") { header("X-Api-Key", KEY) }.andExpect { status { isForbidden() } }
        (mvc.get("/api/pr/v1/pitches") { header("X-Api-Key", KEY) }.andReturn().response.status) shouldBe 200
    }
}
