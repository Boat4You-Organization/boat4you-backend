package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.calls.CallsTestJson
import hr.workspace.boat4you.domains.pr.jpa.PrPitchStatus
import hr.workspace.boat4you.domains.pr.service.PrContactService
import hr.workspace.boat4you.domains.pr.service.PrException
import hr.workspace.boat4you.domains.pr.service.PrPitchService
import hr.workspace.boat4you.domains.pr.service.PrStudyService
import hr.workspace.boat4you.domains.pr.web.AdminPrController
import hr.workspace.boat4you.domains.pr.web.PrErrorAdvice
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.core.GrantedAuthorityDefaults
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** The PR admin API is SYSTEM_ADMIN only: journalist contacts never reach anyone else. Errors are `{code, message}`. */
@SpringJUnitConfig(AdminPrSecurityTest.Config::class)
class AdminPrSecurityTest {
    @Configuration
    @EnableMethodSecurity
    internal class Config {
        companion object {
            @Bean
            @JvmStatic
            fun grantedAuthorityDefaults(): GrantedAuthorityDefaults = GrantedAuthorityDefaults("")
        }

        @Bean
        fun prContactService(): PrContactService = mock(PrContactService::class.java)

        @Bean
        fun prStudyService(): PrStudyService = mock(PrStudyService::class.java)

        @Bean
        fun prPitchService(): PrPitchService = mock(PrPitchService::class.java)

        @Bean
        fun adminPrController(
            service: PrContactService,
            studies: PrStudyService,
            pitches: PrPitchService,
        ) = AdminPrController(service, studies, pitches)
    }

    @Autowired private lateinit var controller: AdminPrController

    @Autowired private lateinit var service: PrContactService

    @Autowired private lateinit var studies: PrStudyService

    @Autowired private lateinit var pitches: PrPitchService

    private val mvc: MockMvc by lazy {
        MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(PrErrorAdvice(), ApiErrorHandler())
            .setMessageConverters(*CallsTestJson.converters())
            .build()
    }

    @BeforeEach
    fun resetMocks() = reset(service, studies, pitches)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun signIn(vararg roles: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("someone@example.com", null, roles.map { SimpleGrantedAuthority(it) })
    }

    private fun json(b: String): MockHttpServletRequestDsl.() -> Unit =
        {
            contentType = MediaType.APPLICATION_JSON
            content = b
        }

    private fun every(): List<ResultActionsDsl> =
        listOf(
            mvc.get("/admin/pr/outlets"),
            mvc.post("/admin/pr/outlets", dsl = json("""{"name":"X","market":"GB","tier":1}""")),
            mvc.patch("/admin/pr/outlets/1", dsl = json("""{"tier":2}""")),
            mvc.get("/admin/pr/contacts?market=GB&status=ACTIVE&q=x"),
            mvc.post("/admin/pr/contacts", dsl = json("""{"email":"a@example.com"}""")),
            mvc.patch("/admin/pr/contacts/1", dsl = json("""{"status":"CONSENTED"}""")),
            mvc.post("/admin/pr/contacts/import") {
                contentType = MediaType.parseMediaType("text/csv")
                content = "market,outlet,tier,email\n"
            },
            mvc.post("/admin/pr/contacts/1/opt-out"),
            mvc.post("/admin/pr/contacts/1/bounce"),
            mvc.get("/admin/pr/studies"),
            mvc.post("/admin/pr/studies", dsl = json("""{"number":1,"siteId":"boat4you","title":"T"}""")),
            mvc.patch("/admin/pr/studies/1", dsl = json("""{"status":"LIVE"}""")),
            mvc.get("/admin/pr/pitches?studyId=1&status=DRAFT"),
            mvc.post("/admin/pr/pitches", dsl = json("""{"contactId":1,"template":"DATA_PITCH","subject":"S","body":"B"}""")),
            mvc.patch("/admin/pr/pitches/1", dsl = json("""{"body":"B2"}""")),
            mvc.post("/admin/pr/pitches/1/follow-up", dsl = json("""{"body":"F"}""")),
            mvc.patch("/admin/pr/pitches/1/status", dsl = json("""{"status":"REPLIED","replyNote":"n"}""")),
        )

    @Test
    fun adminPrRequiresSystemAdmin() {
        signIn("USER", "MANAGER")
        every().forEach { it.andExpect { status { isForbidden() } } }
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        every().forEach { it.andExpect { status { isForbidden() } } }
        verifyNoInteractions(service, studies, pitches)
    }

    @Test
    fun `SYSTEM_ADMIN passes and PR errors come back as code and message`() {
        signIn("SYSTEM_ADMIN")
        mvc.get("/admin/pr/outlets").andExpect { status { isOk() } }
        mvc.get("/admin/pr/studies").andExpect { status { isOk() } }
        mvc.get("/admin/pr/pitches?studyId=1&status=SENT").andExpect { status { isOk() } }
        verify(pitches).list(1, PrPitchStatus.SENT)
        `when`(service.optOut(anyLong(), org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(PrException(HttpStatus.NOT_FOUND, "CONTACT_NOT_FOUND", "Contact 9 not found"))
        mvc.post("/admin/pr/contacts/9/opt-out").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("CONTACT_NOT_FOUND") }
            jsonPath("$.message") { value("Contact 9 not found") }
        }
        mvc.patch("/admin/pr/contacts/1", dsl = json("""{"status":"NOPE"}""")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION") }
        }
    }
}
