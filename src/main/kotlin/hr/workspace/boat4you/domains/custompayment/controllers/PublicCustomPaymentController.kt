package hr.workspace.boat4you.domains.custompayment.controllers

import hr.workspace.boat4you.common.ratelimit.ClientIp
import hr.workspace.boat4you.domains.custompayment.dto.CreateCustomPaymentRequest
import hr.workspace.boat4you.domains.custompayment.dto.CreateCustomPaymentResponse
import hr.workspace.boat4you.domains.custompayment.dto.CustomPaymentConfigDto
import hr.workspace.boat4you.domains.custompayment.dto.CustomPaymentStatusDto
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentService
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The secret `/pay/<linkKey>` page of the 7 sites (no login; the link key is the credential, so responses are never
 * cached). `/public/` is permitAll in SecurityConfiguration; create and status are rate-limited per IP in
 * PublicEndpointRateLimiter. Errors: 400 invalid fields, 404 (8002) wrong site / link key or unknown payment,
 * 429 (8003) too many attempts per e-mail, 502 (8004) Mollie error, 503 (8001) Mollie not configured.
 */
@RestController
@RequestMapping("/public/payments/custom")
class PublicCustomPaymentController(
    private val service: CustomPaymentService,
) {
    @Operation(summary = "Form settings for a site's secret payment link (404 for a wrong site / link key)")
    @GetMapping("/config")
    fun config(
        @RequestParam site: String,
        @RequestParam linkKey: String,
    ): ResponseEntity<CustomPaymentConfigDto> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.config(site, linkKey))

    @Operation(
        summary = "Start a card payment: creates the Mollie payment and returns the hosted checkout URL",
        description = "The same data again while the first checkout is still open returns that checkout (no second order).",
    )
    @PostMapping("/orders")
    fun create(
        @Valid @RequestBody body: CreateCustomPaymentRequest,
        request: HttpServletRequest,
    ): ResponseEntity<CreateCustomPaymentResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.create(body, ClientIp.of(request)))

    @Operation(summary = "Status of a payment by its public token (success page); 404 when unknown or older than 30 days")
    @GetMapping("/orders/{token}")
    fun status(
        @PathVariable token: String,
    ): ResponseEntity<CustomPaymentStatusDto> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(token))
}
