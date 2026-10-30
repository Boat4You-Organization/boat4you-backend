package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.domains.contactcenter.service.ContactLinker
import hr.workspace.boat4you.domains.contactcenter.service.ContactService
import hr.workspace.boat4you.domains.contactcenter.service.ProactiveMessaging
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The Contact Center admin API is SYSTEM_ADMIN only (BACKEND-27): the real @PreAuthorize through Spring's method security
 * with the production role setup (no "ROLE_" prefix). Any other user - and an anonymous one - gets 403 code 1000 on all 14
 * endpoints and nothing behind the controller is touched.
 */
@SpringJUnitConfig(AdminContactCenterControllerSecurityTests.Config::class)
class AdminContactCenterControllerSecurityTests {
    @Configuration
    @EnableMethodSecurity
    internal class Config {
        companion object {
            @Bean
            @JvmStatic
            fun grantedAuthorityDefaults(): GrantedAuthorityDefaults = GrantedAuthorityDefaults("")
        }

        @Bean fun conversations(): ConversationQuery = mock(ConversationQuery::class.java)

        @Bean fun linker(): ContactLinker = mock(ContactLinker::class.java)

        @Bean fun sessions(): AiChatSessionRepository = mock(AiChatSessionRepository::class.java)

        @Bean fun chat(): AiChatService = mock(AiChatService::class.java)

        @Bean fun live(): LiveView = mock(LiveView::class.java)

        @Bean fun proactive(): ProactiveMessaging = mock(ProactiveMessaging::class.java)

        @Bean fun contactQuery(): ContactQuery = mock(ContactQuery::class.java)

        @Bean fun contacts(): ContactService = mock(ContactService::class.java)

        @Bean fun stats(): StatsQuery = mock(StatsQuery::class.java)

        @Bean
        fun adminContactCenterController(
            conversations: ConversationQuery,
            linker: ContactLinker,
            sessions: AiChatSessionRepository,
            chat: AiChatService,
            live: LiveView,
            proactive: ProactiveMessaging,
            contactQuery: ContactQuery,
            contacts: ContactService,
            stats: StatsQuery,
        ) = AdminContactCenterController(conversations, linker, sessions, chat, live, proactive, contactQuery, contacts, stats)
    }

    @Autowired private lateinit var controller: AdminContactCenterController

    @Autowired private lateinit var conversations: ConversationQuery

    @Autowired private lateinit var linker: ContactLinker

    @Autowired private lateinit var sessions: AiChatSessionRepository

    @Autowired private lateinit var chat: AiChatService

    @Autowired private lateinit var live: LiveView

    @Autowired private lateinit var proactive: ProactiveMessaging

    @Autowired private lateinit var contactQuery: ContactQuery

    @Autowired private lateinit var contacts: ContactService

    @Autowired private lateinit var stats: StatsQuery

    private val mvc: MockMvc by lazy {
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(ApiErrorHandler()).build()
    }

    private fun mocks() = arrayOf(conversations, linker, sessions, chat, live, proactive, contactQuery, contacts, stats)

    @BeforeEach
    fun resetMocks() = reset(*mocks())

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun json(body: String): org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit =
        {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun every(): List<ResultActionsDsl> =
        listOf(
            mvc.get("/admin/cc/conversations"),
            mvc.get("/admin/cc/conversations/unread-count"),
            mvc.get("/admin/cc/conversations/1"),
            mvc.post("/admin/cc/conversations/1/read"),
            mvc.post("/admin/cc/conversations/1/take-over"),
            mvc.post("/admin/cc/conversations/1/return-to-ai"),
            mvc.get("/admin/cc/live"),
            mvc.post("/admin/cc/live/a0000000-0000-4000-8000-00000000000a/message", dsl = json("""{"text":"Bok"}""")),
            mvc.get("/admin/cc/contacts"),
            mvc.get("/admin/cc/contacts/1"),
            mvc.patch("/admin/cc/contacts/1", dsl = json("""{"name":"Ana"}""")),
            mvc.put("/admin/cc/contacts/1/tags", dsl = json("""{"tags":["vip"]}""")),
            mvc.post("/admin/cc/contacts/1/merge", dsl = json("""{"sourceId":2}""")),
            mvc.get("/admin/cc/stats"),
        )

    @Test
    fun `a non-admin user gets 403 code 1000 on every endpoint`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("someone@example.com", null, listOf(SimpleGrantedAuthority("USER"), SimpleGrantedAuthority("MANAGER")))
        every().forEach {
            it.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(1000) }
            }
        }
        verifyNoInteractions(*mocks())
    }

    @Test
    fun `an anonymous request gets 403 on every endpoint`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        every().forEach {
            it.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(1000) }
            }
        }
        verifyNoInteractions(*mocks())
    }
}
