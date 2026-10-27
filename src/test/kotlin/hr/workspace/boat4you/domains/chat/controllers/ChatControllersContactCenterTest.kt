package hr.workspace.boat4you.domains.chat.controllers

import hr.workspace.boat4you.common.models.UserDomainEntity
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.domains.chat.service.AiChatService.MessageOutcome
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.Optional

/**
 * What the two chat controllers hand to [AiChatService] for the contact center (BACKEND-19/-27, WEB-12): a retried
 * visitor POST is answered from the store before the session is touched, and Mario's reply carries his user id.
 */
class ChatControllersContactCenterTest {
    private val calls = mutableListOf<String>()
    private val session =
        AiChatSession().apply {
            id = 5
            token = "tok-5"
        }
    private val stored =
        AiChatMessage().apply {
            id = 11
            sessionId = 5
            role = "USER"
            content = "hi"
        }
    private val chat =
        mock(AiChatService::class.java) { inv ->
            calls += inv.method.name
            when (inv.method.name) {
                "isEnabled" -> true
                "getSession" -> session
                "replayIfDuplicate" -> if (inv.arguments[1] == "c-0001-abcd") MessageOutcome.Replied(listOf(stored)) else null
                "handleVisitorMessage" -> MessageOutcome.Replied(listOf(stored))
                "adminReply" ->
                    AiChatMessage().apply {
                        id = 12
                        role = "ADMIN"
                        content = inv.arguments[1] as String
                        authorUserId = inv.arguments[2] as Long?
                    }
                else -> null
            }
        }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `a retried POST is answered from the store before the session is reopened`() {
        val res = PublicAiChatController(chat).postMessage("tok-5", PublicAiChatController.MessageRequest("hi", null, " c-0001-abcd "))
        res.statusCode shouldBe HttpStatus.OK
        (res.body as PublicAiChatController.SessionStateDto).messages.map { it.id } shouldBe listOf(11L)
        calls shouldNotContain "reopenIfClosed"
        calls shouldNotContain "handleVisitorMessage"
    }

    @Test
    fun `a POST without id goes the usual way (WEB-12)`() {
        PublicAiChatController(chat).postMessage("tok-5", PublicAiChatController.MessageRequest("hi")).statusCode shouldBe HttpStatus.OK
        calls shouldContainInOrder listOf("replayIfDuplicate", "reopenIfClosed", "handleVisitorMessage")
    }

    @Test
    fun `Mario's reply is stored with his user id, the class stays SYSTEM_ADMIN only (BACKEND-27)`() {
        val principal =
            UserDomainEntity(
                UserEntity().apply {
                    id = 7L
                    email = "mario@boat4you.com"
                },
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority("SYSTEM_ADMIN")))
        val sessions = mock(AiChatSessionRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(session) else null }
        AdminAiChatController(sessions, mock(AiChatMessageRepository::class.java), chat)
            .reply(5, AdminAiChatController.ReplyRequest("Pozdrav"))
            .body!!
            .authorUserId shouldBe 7L
        AdminAiChatController::class.java.getAnnotation(PreAuthorize::class.java).value shouldBe "hasRole('SYSTEM_ADMIN')"
    }
}
