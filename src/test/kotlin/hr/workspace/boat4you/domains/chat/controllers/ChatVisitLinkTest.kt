package hr.workspace.boat4you.domains.chat.controllers

import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.domains.contactcenter.service.ChatVisitLinker
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

/** P3 task 9: a session created by the shared widget is pointed at the tracker's visit; today's ChatWidget is unchanged. */
class ChatVisitLinkTest {
    private val session =
        AiChatSession().apply {
            id = 7
            token = "tok-7"
            siteId = "catamaran-greece"
        }
    private val chat =
        mock(AiChatService::class.java) { inv ->
            when (inv.method.name) {
                "isEnabled" -> true
                "createSession" -> session
                "isAiPaused" -> false
                else -> null
            }
        }
    private val links = mutableListOf<List<Any>>()
    private val visitor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val visit = "bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb"

    private fun create(
        linker: ChatVisitLinker,
        visitorKey: String?,
        visitKey: String?,
    ) = PublicAiChatController(chat, linker).createSession(
        PublicAiChatController.CreateSessionRequest(locale = "en", name = "Ana", visitorKey = visitorKey, visitKey = visitKey),
        MockHttpServletRequest().apply { addHeader("Origin", "https://www.catamaran-charter-greece.com") },
    )

    private val recording =
        ChatVisitLinker { chatSessionId, siteId, visitorKey, visitKey ->
            links += listOf(chatSessionId, siteId, visitorKey, visitKey)
            true
        }

    @Test
    fun `a session created with visitorKey and visitKey is linked to the visit`() {
        create(recording, visitor, visit).statusCode shouldBe HttpStatus.OK
        links shouldBe listOf(listOf(7L, "catamaran-greece", visitor, visit))
    }

    @Test
    fun `missing or malformed keys create the session as today (WEB-12)`() {
        create(recording, null, null).statusCode shouldBe HttpStatus.OK
        create(recording, visitor, "not-a-uuid").statusCode shouldBe HttpStatus.OK
        create(recording, "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", visit).statusCode shouldBe HttpStatus.OK
        links.shouldBeEmpty()
    }

    @Test
    fun `a linker failure still returns 200 with the session`() {
        val failing = ChatVisitLinker { _, _, _, _ -> error("database down") }
        val res = create(failing, visitor, visit)

        res.statusCode shouldBe HttpStatus.OK
        (res.body as PublicAiChatController.SessionStateDto).token shouldBe "tok-7"
    }

    @Test
    fun `a visit the linker refuses (another site or visitor) leaves the session unlinked and the reply unchanged`() {
        val refusing = ChatVisitLinker { _, _, _, _ -> false }

        create(refusing, visitor, visit).statusCode shouldBe HttpStatus.OK
    }
}
