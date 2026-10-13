package hr.workspace.boat4you.domains.payment.revolut

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrderEventHandler.HandlerResult
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.ResourceAccessException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** HTTP contract of `/webhooks/revolut`: signature first, then dispatch; 204 / 503 / 500 as Revolut expects. */
class RevolutWebhookControllerTests {
    private val nowMs = 1_760_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC)
    private val handler: RevolutOrderEventHandler = mock(RevolutOrderEventHandler::class.java)
    private val client: RevolutMerchantClient = mock(RevolutMerchantClient::class.java)

    private val ownedId = "6516e61c-d279-a454-a837-bc52ce55ed49"
    private val unknownId = "7716e61c-d279-a454-a837-bc52ce55ed50"

    private fun mvc(properties: RevolutProperties = revolutProperties()) =
        MockMvcBuilders
            .standaloneSetup(
                RevolutWebhookController(
                    properties,
                    RevolutSignatureVerifier(clock),
                    RevolutWebhookDispatcher(listOf(handler), client),
                    jacksonObjectMapper(),
                ),
            ).setControllerAdvice(ApiErrorHandler())
            .build()

    private fun body(
        event: String,
        orderId: String,
    ) = """{"event":"$event","order_id":"$orderId","merchant_order_ext_ref":"ignored"}"""

    private fun send(
        body: String,
        ts: Long = nowMs,
        signature: String = "v1=" + revolutSignatureHex(TEST_WEBHOOK_SECRET, ts.toString(), body.toByteArray()),
        properties: RevolutProperties = revolutProperties(),
    ) = mvc(properties).post("/webhooks/revolut") {
        contentType = MediaType.APPLICATION_JSON
        content = body
        header(RevolutSignatureVerifier.TIMESTAMP_HEADER, ts.toString())
        header(RevolutSignatureVerifier.SIGNATURE_HEADER, signature)
    }

    private fun order(
        id: String,
        type: String,
        relatedOrderId: String? = null,
    ) = RevolutOrder(
        id = id,
        type = type,
        state = RevolutOrderStates.COMPLETED,
        amount = 5000,
        currency = "EUR",
        relatedOrderId = relatedOrderId,
        createdAt = Instant.ofEpochMilli(nowMs),
        updatedAt = Instant.ofEpochMilli(nowMs),
    )

    @Test
    fun `bad, stale or missing signature - 400 and nothing is dispatched`() {
        val payload = body(RevolutWebhookEvents.ORDER_COMPLETED, ownedId)
        send(payload, signature = "v1=" + "0".repeat(64)).andExpect { status { isBadRequest() } }
        send(payload, signature = "v1=" + revolutSignatureHex("wsk_wrong", nowMs.toString(), payload.toByteArray()))
            .andExpect { status { isBadRequest() } }
        val stale = nowMs - 300_001
        send(payload, ts = stale).andExpect { status { isBadRequest() } }
        mvc()
            .post("/webhooks/revolut") {
                contentType = MediaType.APPLICATION_JSON
                content = payload
            }.andExpect { status { isBadRequest() } }

        verifyNoInteractions(handler, client)
    }

    @Test
    fun `feature disabled - 400 even for a correctly signed request`() {
        send(body(RevolutWebhookEvents.ORDER_COMPLETED, ownedId), properties = revolutProperties(profiles = listOf("prod")))
            .andExpect { status { isBadRequest() } }
        send(body(RevolutWebhookEvents.ORDER_COMPLETED, ownedId), properties = revolutProperties(secretKey = ""))
            .andExpect { status { isBadRequest() } }

        verifyNoInteractions(handler, client)
    }

    @Test
    fun `signed but unusable body - malformed JSON 400, no order id 204, nothing dispatched`() {
        send("""{"event":""").andExpect { status { isBadRequest() } }
        send("""{"event":"ORDER_COMPLETED"}""").andExpect { status { isNoContent() } }
        send(body(RevolutWebhookEvents.ORDER_COMPLETED, "../../api/refunds")).andExpect { status { isNoContent() } }

        verifyNoInteractions(handler, client)
    }

    @Test
    fun `unknown order - 204, one Revolut read only for ORDER_COMPLETED`() {
        send(body(RevolutWebhookEvents.ORDER_FAILED, unknownId)).andExpect { status { isNoContent() } }
        verifyNoInteractions(client)

        `when`(client.getOrder(unknownId)).thenReturn(null)
        send(body(RevolutWebhookEvents.ORDER_COMPLETED, unknownId)).andExpect { status { isNoContent() } }
        verify(client).getOrder(unknownId)
        verify(handler, never()).onEvent(RevolutWebhookEvents.ORDER_COMPLETED, unknownId)
    }

    @Test
    fun `owned order - the owning handler gets the event, HANDLED is 204`() {
        `when`(handler.ownsOrder(ownedId)).thenReturn(true)
        `when`(handler.onEvent(RevolutWebhookEvents.ORDER_COMPLETED, ownedId)).thenReturn(HandlerResult.HANDLED)

        send(body(RevolutWebhookEvents.ORDER_COMPLETED, ownedId)).andExpect { status { isNoContent() } }

        verify(handler).onEvent(RevolutWebhookEvents.ORDER_COMPLETED, ownedId)
        verifyNoInteractions(client)
    }

    @Test
    fun `TRANSIENT - 503 so Revolut retries`() {
        `when`(handler.ownsOrder(ownedId)).thenReturn(true)
        `when`(handler.onEvent(RevolutWebhookEvents.ORDER_COMPLETED, ownedId)).thenReturn(HandlerResult.TRANSIENT)
        send(body(RevolutWebhookEvents.ORDER_COMPLETED, ownedId)).andExpect { status { isServiceUnavailable() } }

        `when`(client.getOrder(unknownId)).thenThrow(ResourceAccessException("timeout"))
        send(body(RevolutWebhookEvents.ORDER_COMPLETED, unknownId)).andExpect { status { isServiceUnavailable() } }
    }

    @Test
    fun `completed refund order of an owned payment - owner onRefund, 204`() {
        val refundId = "8816e61c-d279-a454-a837-bc52ce55ed51"
        val refund = order(refundId, RevolutOrderTypes.REFUND, relatedOrderId = ownedId)
        `when`(handler.ownsOrder(ownedId)).thenReturn(true)
        `when`(client.getOrder(refundId)).thenReturn(refund)

        send(body(RevolutWebhookEvents.ORDER_COMPLETED, refundId)).andExpect { status { isNoContent() } }

        verify(handler).onRefund(ownedId, refund)
        verify(handler, never()).onEvent(RevolutWebhookEvents.ORDER_COMPLETED, refundId)
    }

    @Test
    fun `unexpected handler failure - 500, never a 2xx`() {
        `when`(handler.ownsOrder(ownedId)).thenReturn(true)
        `when`(handler.onEvent(RevolutWebhookEvents.ORDER_COMPLETED, ownedId)).thenThrow(IllegalStateException("db down"))

        send(body(RevolutWebhookEvents.ORDER_COMPLETED, ownedId)).andExpect { status { isInternalServerError() } }
    }
}
