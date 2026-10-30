package hr.workspace.boat4you.domains.contactcenter.controllers

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEventRepository
import hr.workspace.boat4you.domains.contactcenter.service.AiCircuitBreaker
import hr.workspace.boat4you.domains.contactcenter.service.CcAiSettings
import org.junit.jupiter.api.AfterEach
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
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.core.GrantedAuthorityDefaults
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The AI breaker, the cc.* AI settings and the security log are SYSTEM_ADMIN only (BACKEND-27): the real @PreAuthorize
 * through Spring's method security with the production role setup (no "ROLE_" prefix). Any other user - and an anonymous
 * one - gets 403 code 1000 and nothing behind the controller is touched.
 */
@SpringJUnitConfig(AdminCcAiControllerSecurityTests.Config::class)
class AdminCcAiControllerSecurityTests {
    @Configuration
    @EnableMethodSecurity
    internal class Config {
        companion object {
            @Bean
            @JvmStatic
            fun grantedAuthorityDefaults(): GrantedAuthorityDefaults = GrantedAuthorityDefaults("")
        }

        @Bean
        fun breaker(): AiCircuitBreaker = mock(AiCircuitBreaker::class.java)

        @Bean
        fun settings(): CcAiSettings = mock(CcAiSettings::class.java)

        @Bean
        fun events(): CcSecurityEventRepository = mock(CcSecurityEventRepository::class.java)

        @Bean
        fun adminCcAiController(
            breaker: AiCircuitBreaker,
            settings: CcAiSettings,
            events: CcSecurityEventRepository,
        ) = AdminCcAiController(breaker, settings, events)
    }

    @Autowired
    private lateinit var controller: AdminCcAiController

    @Autowired
    private lateinit var breaker: AiCircuitBreaker

    @Autowired
    private lateinit var settings: CcAiSettings

    @Autowired
    private lateinit var events: CcSecurityEventRepository

    private val mvc: MockMvc by lazy {
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(ApiErrorHandler()).build()
    }

    @BeforeEach
    fun resetMocks() = reset(breaker, settings, events)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun signIn(vararg roles: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("someone@example.com", null, roles.map { SimpleGrantedAuthority(it) })
    }

    private fun every(): List<ResultActionsDsl> =
        listOf(
            mvc.get("/admin/cc/ai/breaker"),
            mvc.post("/admin/cc/ai/breaker/open") {
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            },
            mvc.post("/admin/cc/ai/breaker/close") {
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            },
            mvc.get("/admin/cc/ai/settings"),
            mvc.put("/admin/cc/ai/settings") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"key":"cc.handoff.minutes","value":"7"}"""
            },
            mvc.get("/admin/cc/ai/security-events"),
        )

    @Test
    fun `a non-admin user gets 403 on every endpoint`() {
        signIn("USER", "MANAGER")
        every().forEach {
            it.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(1000) }
            }
        }
        verifyNoInteractions(breaker, settings, events)
    }

    @Test
    fun `an anonymous request gets 403`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        every().forEach { it.andExpect { status { isForbidden() } } }
        verifyNoInteractions(breaker, settings, events)
    }

    @Test
    fun `SYSTEM_ADMIN - unknown site and invalid value are 400, a valid open is applied`() {
        signIn("SYSTEM_ADMIN")
        `when`(settings.update("cc.handoff.minutes", "0")).thenReturn(false)
        mvc
            .put("/admin/cc/ai/settings") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"key":"cc.handoff.minutes","value":"0"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("invalid") }
            }
        mvc
            .post("/admin/cc/ai/breaker/open") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"siteId":"evil"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("unknown site") }
            }
        mvc
            .post("/admin/cc/ai/breaker/open") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"siteId":"catamaran-greece"}"""
            }.andExpect { status { isOk() } }
        verify(breaker).open("catamaran-greece", "someone@example.com")
    }
}
