package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.calls.service.AdminCallService
import hr.workspace.boat4you.domains.calls.web.AdminCallController
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verifyNoInteractions
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

/** The Call centar admin API is SYSTEM_ADMIN only (design §2.5, §6.6): transcripts and recordings never reach anyone else. */
@SpringJUnitConfig(AdminCallSecurityTests.Config::class)
class AdminCallSecurityTests {
    @Configuration
    @EnableMethodSecurity
    internal class Config {
        companion object {
            @Bean
            @JvmStatic
            fun grantedAuthorityDefaults(): GrantedAuthorityDefaults = GrantedAuthorityDefaults("")
        }

        @Bean
        fun adminCallService(): AdminCallService = mock(AdminCallService::class.java)

        @Bean
        fun adminCallController(service: AdminCallService) = AdminCallController(service)
    }

    @Autowired private lateinit var controller: AdminCallController

    @Autowired private lateinit var service: AdminCallService

    private val mvc: MockMvc by lazy {
        MockMvcBuilders
            .standaloneSetup(
                controller,
            ).setControllerAdvice(ApiErrorHandler())
            .setMessageConverters(*CallsTestJson.converters())
            .build()
    }

    @BeforeEach
    fun resetMocks() = reset(service)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun signIn(vararg roles: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("someone@example.com", null, roles.map { SimpleGrantedAuthority(it) })
    }

    private fun json(b: String): org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit =
        {
            contentType = MediaType.APPLICATION_JSON
            content = b
        }

    private fun every(): List<ResultActionsDsl> =
        listOf(
            mvc.get("/admin/calls"),
            mvc.get("/admin/calls/1"),
            mvc.get("/admin/calls/1/audio"),
            mvc.get("/admin/calls/inquiries"),
            mvc.post("/admin/calls/inquiries/1/call-now", dsl = json("""{"confirmOutsideWindow":false}""")),
            mvc.post("/admin/calls/inquiries/1/human-takeover"),
            mvc.post("/admin/calls/inquiries/1/offer-sent", dsl = json("""{"note":"x"}""")),
            mvc.post("/admin/calls/inquiries/1/do-not-call"),
            mvc.post("/admin/calls/1/resolve"),
            mvc.get("/admin/calls/settings"),
            mvc.put("/admin/calls/settings", dsl = json("""{"calls.enabled":"true"}""")),
        )

    @Test
    fun `a non-admin user gets 403 on every endpoint`() {
        signIn("USER", "MANAGER")
        every().forEach { it.andExpect { status { isForbidden() } } }
        verifyNoInteractions(service)
    }

    @Test
    fun `an anonymous request gets 403`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        every().forEach { it.andExpect { status { isForbidden() } } }
        verifyNoInteractions(service)
    }

    @Test
    fun `SYSTEM_ADMIN passes`() {
        signIn("SYSTEM_ADMIN")
        mvc.get("/admin/calls/settings").andExpect { status { isOk() } }
    }
}
