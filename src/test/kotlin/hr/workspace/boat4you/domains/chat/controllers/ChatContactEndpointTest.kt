package hr.workspace.boat4you.domains.chat.controllers

import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.domains.chat.service.AiChatService.ContactOutcome
import hr.workspace.boat4you.domains.contactcenter.service.ChatVisitLinker
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus

/** The widget's contact form (31.10.2026): the service decides, the endpoint maps its answer to a status the widget shows. */
class ChatContactEndpointTest {
    private var outcome = ContactOutcome.OK
    private val session =
        AiChatSession().apply {
            id = 5
            token = "tok-5"
            locale = "en"
        }
    private val chat =
        mock(AiChatService::class.java) { inv ->
            when (inv.method.name) {
                "isEnabled" -> true
                "getSession" -> if (inv.arguments[0] == "tok-5") session else null
                "setVisitorContact" -> outcome
                else -> null
            }
        }
    private val controller = PublicAiChatController(chat, ChatVisitLinker { _, _, _, _ -> false })

    private fun post(token: String = "tok-5") = controller.setContact(token, PublicAiChatController.ContactRequest("a@b.com", "+385 91 234 5678"))

    @Test
    fun `the contact form answers 200, 400 with the reason, 404 and 423`() {
        post().statusCode shouldBe HttpStatus.OK
        mapOf(ContactOutcome.INVALID_EMAIL to "invalid_email", ContactOutcome.INVALID_PHONE to "invalid_phone", ContactOutcome.EMPTY to "empty")
            .forEach { (o, error) ->
                outcome = o
                post().let {
                    it.statusCode shouldBe HttpStatus.BAD_REQUEST
                    (it.body as Map<*, *>)["error"] shouldBe error
                }
            }
        outcome = ContactOutcome.LOCKED
        post().statusCode shouldBe HttpStatus.LOCKED
        post("nope").statusCode shouldBe HttpStatus.NOT_FOUND
    }
}
