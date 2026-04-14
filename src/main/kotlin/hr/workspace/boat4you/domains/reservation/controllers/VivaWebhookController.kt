package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.reservation.dto.VivaVerificationKeyDto
import hr.workspace.boat4you.domains.reservation.dto.VivaWebhookPayloadDto
import hr.workspace.boat4you.domains.reservation.service.VivaPaymentService
import hr.workspace.boat4you.domains.reservation.service.VivaVerificationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

@Tag(name = "Payments")
@Validated
@Controller
@RequestMapping("/webhooks/viva")
class VivaWebhookController(
    private val vivaPaymentService: VivaPaymentService,
    private val vivaVerificationService: VivaVerificationService,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @Operation(summary = "Viva webhook URL verification (GET). Viva calls this when you click Verify in dashboard.")
    @GetMapping
    fun verifyVivaWebhookUrl(): ResponseEntity<VivaVerificationKeyDto> {
        logger.debug("Received Viva webhook verification request")

        val key = vivaVerificationService.fetchVerificationKey()
        return ResponseEntity.ok(key).also {
            logger.debug("Processed Viva webhook verification request")
        }
    }

    @Operation(summary = "Called by Viva when payment process is done")
    @PostMapping
    fun handleVivaWebhook(
        @RequestBody request: VivaWebhookPayloadDto,
    ): ResponseEntity<String> {
        try {
            logger.debug(
                "Received Viva webhook: typeId={} reservationId={} orderCode={} transactionId={} sourceCode={} retryCount={}",
                request.eventTypeId,
                request.getReservationIdTag(),
                request.eventData?.orderCode,
                request.eventData?.transactionId,
                request.eventData?.sourceCode,
                request.retryCount,
            )

            vivaPaymentService.handleWebhookEvent(request)

            return ResponseEntity.ok("OK")
        } catch (ex: Exception) {
            logger.error("Error handling Viva webhook", ex)
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Webhook processing error")
        }
    }
}
