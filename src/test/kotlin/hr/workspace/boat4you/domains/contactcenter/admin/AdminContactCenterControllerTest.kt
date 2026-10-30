package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.domains.contactcenter.service.ContactLinker
import hr.workspace.boat4you.domains.contactcenter.service.ContactService
import hr.workspace.boat4you.domains.contactcenter.service.ProactiveMessaging
import hr.workspace.boat4you.domains.contactcenter.service.ProactiveResult
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Optional

/** Standalone MockMvc over the controller with mocked services: validation and the order of calls. */
class AdminContactCenterControllerTest {
    private val conversations = mock(ConversationQuery::class.java)
    private val linker = mock(ContactLinker::class.java)
    private val sessions = mock(AiChatSessionRepository::class.java)
    private val chat = mock(AiChatService::class.java)
    private val stats = mock(StatsQuery::class.java)
    private val controller =
        AdminContactCenterController(
            conversations,
            linker,
            sessions,
            chat,
            mock(LiveView::class.java),
            mock(ProactiveMessaging::class.java),
            mock(ContactQuery::class.java),
            mock(ContactService::class.java),
            stats,
        )
    private val mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(ApiErrorHandler()).build()

    @Test
    fun `unknown site id is 400`() {
        mvc.get("/admin/cc/conversations?siteId=evil").andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("unknown site") }
        }
        mvc.get("/admin/cc/stats?siteId=evil").andExpect { status { isBadRequest() } }
        mvc.get("/admin/cc/conversations?status=DROP").andExpect { status { isBadRequest() } }
        verifyNoInteractions(conversations, stats)
    }

    @Test
    fun `return-to-ai on a flagged conversation is 409`() {
        val flagged =
            AiChatSession().apply {
                id = 5
                status = AiChatSession.STATUS_HUMAN
                this.flagged = true
            }
        `when`(sessions.findById(5L)).thenReturn(Optional.of(flagged))
        `when`(chat.returnToAi(flagged, true)).thenReturn(false)
        mvc.post("/admin/cc/conversations/5/return-to-ai").andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("not_allowed") }
        }
        `when`(sessions.findById(6L)).thenReturn(Optional.empty())
        mvc.post("/admin/cc/conversations/6/take-over").andExpect { status { isNotFound() } }
    }

    @Test
    fun `a stats range over 400 days is 400`() {
        mvc.get("/admin/cc/stats?from=2025-01-01T00:00:00Z&to=2026-03-01T00:00:00Z").andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("range over 400 days") }
        }
        mvc.get("/admin/cc/stats?from=2026-03-01T00:00:00Z&to=2026-03-01T00:00:00Z").andExpect { status { isBadRequest() } }
        verifyNoInteractions(stats)
        val from = Instant.parse("2026-01-01T00:00:00Z")
        val to = Instant.parse("2026-02-01T00:00:00Z")
        mvc.get("/admin/cc/stats?from=$from&to=$to").andExpect { status { isOk() } }
    }

    @Test
    fun `detail links the contact before reading`() {
        `when`(conversations.detail(anyLong())).thenReturn(null)
        mvc.get("/admin/cc/conversations/9").andExpect { status { isNotFound() } }
        inOrder(linker, conversations).apply {
            verify(linker).ensureForSession(9)
            verify(conversations).detail(9)
        }
    }

    @Test
    fun `an invalid text for a live visitor is 400`() {
        val proactive = mock(ProactiveMessaging::class.java)
        `when`(proactive.enqueueProactive(anyString(), anyString(), anyLong())).thenReturn(ProactiveResult.INVALID_TEXT)
        val c =
            AdminContactCenterController(
                conversations,
                linker,
                sessions,
                chat,
                mock(LiveView::class.java),
                proactive,
                mock(ContactQuery::class.java),
                mock(ContactService::class.java),
                stats,
            )
        MockMvcBuilders
            .standaloneSetup(c)
            .build()
            .post("/admin/cc/live/k/message") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"   "}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.result") { value("INVALID_TEXT") }
            }
    }
}
