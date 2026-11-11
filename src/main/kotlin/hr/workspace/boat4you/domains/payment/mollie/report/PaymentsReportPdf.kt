package hr.workspace.boat4you.domains.payment.mollie.report

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.springframework.stereotype.Component
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import java.io.ByteArrayOutputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The accounting report as an A4 landscape PDF, in Croatian (it is read by the office and its bookkeeper). Thymeleaf
 * renders `templates/report/paymentsReport.html`, openhtmltopdf turns it into the PDF. The Inter font is embedded: the
 * built-in PDF fonts have no č, ć or đ.
 */
@Component
class PaymentsReportPdf(
    private val templateEngine: SpringTemplateEngine,
) {
    fun render(report: PaymentsReport): ByteArray {
        val html = templateEngine.process(TEMPLATE_NAME, Context(CROATIAN, variables(report)))
        ByteArrayOutputStream().use { out ->
            PdfRendererBuilder()
                .useFastMode()
                .useFont({ font("Inter-Regular.ttf") }, FONT_FAMILY, WEIGHT_REGULAR, FontStyle.NORMAL, true)
                .useFont({ font("Inter-SemiBold.ttf") }, FONT_FAMILY, WEIGHT_SEMIBOLD, FontStyle.NORMAL, true)
                .withHtmlContent(html, BASE_URI)
                .toStream(out)
                .run()
            return out.toByteArray()
        }
    }

    internal fun variables(report: PaymentsReport): Map<String, Any?> {
        val payments = report.payments
        val ledger = report.ledger
        return mapOf(
            "period" to "${date(report.from)} – ${date(report.to)}",
            "generatedAt" to dateTime(report.generatedAt),
            "payments" to payments.map { it.toRow() },
            "paymentsCount" to payments.size,
            "paymentsAmount" to money(payments.sumOf { it.amountMinor }),
            "paymentsFee" to money(payments.sumOf { it.feeMinor ?: 0 }),
            "paymentsNet" to money(payments.sumOf { it.amountMinor - (it.feeMinor ?: 0) }),
            "paymentsBack" to money(payments.sumOf { it.moneyBackMinor }),
            "feesMissing" to payments.count { it.feeMinor == null },
            "ledgerAvailable" to (ledger != null),
            "ledgerComplete" to (ledger?.complete ?: true),
            "moneyBack" to ledger?.moneyBack.orEmpty().map { it.toRow() },
            "moneyBackTotals" to totals(ledger?.moneyBack.orEmpty()),
            "payouts" to ledger?.payouts.orEmpty().map { it.toView() },
            "payoutsTotal" to money(ledger?.payouts.orEmpty().sumOf { it.amountMinor }),
            "notPaidOut" to ledger?.notPaidOut.orEmpty().map { it.toRow() },
            "notPaidOutTotals" to totals(ledger?.notPaidOut.orEmpty()),
        )
    }

    private fun ReportPayment.toRow() =
        PaymentRow(
            at = dateTime(paidAt),
            payer = party.payerName ?: party.payerEmail ?: DASH,
            payerNote = party.payerEmail.takeIf { party.payerName != null },
            reference = party.reference,
            referenceNotes = party.referenceNotes(),
            method = if (method == "KLARNA") "Klarna" else "Kartica",
            mollieId = molliePaymentId,
            amount = money(amountMinor),
            amountNote =
                surchargeMinor?.takeIf { it > 0 }?.let { "rata ${money(baseMinor ?: (amountMinor - it))} + naknada za karticu ${money(it)}" },
            fee = feeMinor?.let { money(it) } ?: DASH,
            net = feeMinor?.let { money(amountMinor - it) } ?: DASH,
            back = if (moneyBackMinor > 0) money(moneyBackMinor) else DASH,
        )

    private fun ReportLedgerLine.toRow() =
        LedgerRow(
            at = dateTime(at),
            label = TYPE_LABELS[type] ?: "Ostalo ($type)",
            payer = party?.let { it.payerName ?: it.payerEmail } ?: DASH,
            payerNote = party?.payerEmail.takeIf { party?.payerName != null },
            reference = party?.reference ?: description ?: DASH,
            referenceNotes = party?.referenceNotes().orEmpty(),
            mollieId = molliePaymentId ?: DASH,
            gross = money(grossMinor),
            fee = if (deductionsMinor != 0L) money(deductionsMinor) else DASH,
            net = money(netMinor),
        )

    private fun ReportPayout.toView() =
        PayoutView(
            at = dateTime(at),
            amount = money(amountMinor),
            ids = listOfNotNull(settlementId, transferId).joinToString(" · ").ifEmpty { null },
            rows = lines.map { it.toRow() },
            totals = totals(lines),
            difference = differenceMinor.takeIf { it != 0L }?.let { money(it) },
        )

    /** Small lines under the reference: what kind of payment it is, and the reservation a link payment is tied to. */
    private fun ReportParty.referenceNotes(): List<String> =
        when (kind) {
            ReportPaymentKind.BOOKING -> emptyList()
            ReportPaymentKind.LINK -> listOfNotNull("link za plaćanje", linkedReservation?.let { "rezervacija $it" })
        }

    private fun totals(lines: List<ReportLedgerLine>) =
        LedgerTotals(
            gross = money(lines.sumOf { it.grossMinor }),
            fee = money(lines.sumOf { it.deductionsMinor }),
            net = money(lines.sumOf { it.netMinor }),
        )

    private fun font(file: String) = requireNotNull(javaClass.getResourceAsStream("/fonts/inter/$file")) { "Missing font resource $file" }

    /** One row of the payments table, already formatted. */
    data class PaymentRow(
        val at: String,
        val payer: String,
        val payerNote: String?,
        val reference: String,
        val referenceNotes: List<String>,
        val method: String,
        val mollieId: String,
        val amount: String,
        val amountNote: String?,
        val fee: String,
        val net: String,
        val back: String,
    )

    /** One movement on the Mollie balance, already formatted. */
    data class LedgerRow(
        val at: String,
        val label: String,
        val payer: String,
        val payerNote: String?,
        val reference: String,
        val referenceNotes: List<String>,
        val mollieId: String,
        val gross: String,
        val fee: String,
        val net: String,
    )

    data class LedgerTotals(
        val gross: String,
        val fee: String,
        val net: String,
    )

    data class PayoutView(
        val at: String,
        val amount: String,
        val ids: String?,
        val rows: List<LedgerRow>,
        val totals: LedgerTotals,
        val difference: String?,
    )

    companion object {
        private const val TEMPLATE_NAME = "report/paymentsReport"
        private const val BASE_URI = "classpath:/templates/report/"
        private const val FONT_FAMILY = "Inter"
        private const val WEIGHT_REGULAR = 400
        private const val WEIGHT_SEMIBOLD = 600
        private const val DASH = "—"
        private val CROATIAN = Locale.forLanguageTag("hr-HR")
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy.")
        private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy. HH:mm").withZone(PaymentsReportService.ZONE)

        /** Mollie's balance transaction types the office is likely to see, in Croatian; any other shows its own name. */
        private val TYPE_LABELS =
            mapOf(
                "payment" to "Uplata",
                "capture" to "Uplata",
                "refund" to "Povrat kupcu",
                "returned-refund" to "Vraćen povrat",
                "chargeback" to "Osporena uplata",
                "chargeback-reversal" to "Poništeno osporavanje",
                "payment-fee" to "Naknada Mollie",
                "failed-payment-fee" to "Naknada Mollie",
                "invoice-compensation" to "Poravnanje po računu Mollie",
                "invoice-rounding-compensation" to "Poravnanje po računu Mollie",
                "balance-correction" to "Ispravak stanja",
                "topup" to "Nadoplata Mollie računa",
                "balance-topup" to "Nadoplata Mollie računa",
                "movement" to "Ostalo kretanje",
                "canceled-transfer" to "Otkazana isplata",
                "returned-transfer" to "Vraćena isplata",
            )

        /** `1.234,56` (Croatian separators, always two decimals; the report is in EUR throughout). */
        fun money(minor: Long): String = DecimalFormat("#,##0.00", SYMBOLS).format(minor.toBigDecimal().movePointLeft(2))

        private val SYMBOLS =
            DecimalFormatSymbols(Locale.ROOT).apply {
                groupingSeparator = '.'
                decimalSeparator = ','
            }

        private fun date(day: LocalDate): String = DATE.format(day)

        private fun dateTime(at: Instant): String = DATE_TIME.format(at)
    }
}
