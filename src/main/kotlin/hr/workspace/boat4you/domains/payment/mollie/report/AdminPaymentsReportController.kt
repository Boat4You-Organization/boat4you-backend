package hr.workspace.boat4you.domains.payment.mollie.report

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import io.swagger.v3.oas.annotations.Operation
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * "PDF for accounting" on the admin Payments page: the Mollie payments, refunds and transfers to the bank of a period
 * as one PDF. Read-only; nothing here moves money.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminPaymentsReportController(
    private val reportService: PaymentsReportService,
    private val pdf: PaymentsReportPdf,
) {
    @Operation(summary = "Accounting report (PDF) of the Mollie payments, refunds and bank transfers; from / to are Croatian calendar days, both inclusive")
    @GetMapping("/payments-report")
    fun report(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ByteArray> {
        if (to.isBefore(from)) throw ParameterValidationException(mapOf("to" to "must not be before from"))
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw ParameterValidationException(mapOf("to" to "at most $MAX_DAYS days"))
        val body = pdf.render(reportService.build(from, to))
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noStore())
            .headers { it.contentDisposition = ContentDisposition.attachment().filename("boat4you-uplate-$from-$to.pdf").build() }
            .body(body)
    }

    private companion object {
        /** One year and a day: a full calendar year fits, a leap one too. */
        const val MAX_DAYS = 367L
    }
}
