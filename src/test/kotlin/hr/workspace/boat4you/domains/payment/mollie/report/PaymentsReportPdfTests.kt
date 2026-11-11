package hr.workspace.boat4you.domains.payment.mollie.report

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** The accounting report really renders to a PDF, with Croatian letters, from the template the application ships. */
class PaymentsReportPdfTests {
    private val pdf =
        PaymentsReportPdf(
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        prefix = "templates/"
                        suffix = ".html"
                        templateMode = TemplateMode.HTML
                        characterEncoding = "UTF-8"
                    },
                )
            },
        )

    private fun text(bytes: ByteArray): String = PDDocument.load(bytes).use { PDFTextStripper().getText(it) }

    private val ana = ReportParty(ReportPaymentKind.BOOKING, "Ana Šimić", "ana.simic@example.com", "1441001/2026")
    private val john = ReportParty(ReportPaymentKind.BOOKING, "John Đorđević-Čačić", "john@example.com", "1441002/2026")
    private val link = ReportParty(ReportPaymentKind.LINK, "Ivo Žužić", "ivo@example.com", "Charter Split 2027", "1441007/2026")

    private fun line(
        at: String,
        type: String,
        paymentId: String?,
        party: ReportParty?,
        gross: Long,
        deductions: Long,
        description: String? = null,
    ) = ReportLedgerLine(Instant.parse(at), type, paymentId, description, party, gross, deductions, gross + deductions)

    private fun sample(): PaymentsReport {
        val anaLine = line("2026-11-03T08:10:00Z", "payment", "tr_Ax7Kq2LmPd", ana, 103_000, -1_854)
        val johnLine = line("2026-11-04T08:10:00Z", "payment", "tr_Bn4Zt8QwRs", john, 257_500, -7_725)
        val linkLine = line("2026-11-06T09:00:00Z", "payment", "tr_Cc9Yh3VbNm", link, 50_000, -915)
        val refund = line("2026-11-08T13:00:00Z", "refund", "tr_Cc9Yh3VbNm", link, -20_000, -25)
        val unknown = line("2026-11-12T10:00:00Z", "payment", "tr_Dd1Uu7WwXx", null, 12_000, -220, "Boat4You 1441011/2026")
        return PaymentsReport(
            from = LocalDate.of(2026, 11, 1),
            to = LocalDate.of(2026, 11, 30),
            generatedAt = Instant.parse("2026-12-01T08:30:00Z"),
            payments =
                listOf(
                    ReportPayment(Instant.parse("2026-11-02T14:21:00Z"), ana, "CARD", "tr_Ax7Kq2LmPd", 103_000, 100_000, 3_000, 1_854),
                    ReportPayment(Instant.parse("2026-11-03T09:05:00Z"), john, "KLARNA", "tr_Bn4Zt8QwRs", 257_500, 250_000, 7_500, 7_725),
                    ReportPayment(Instant.parse("2026-11-05T18:40:00Z"), link, "CARD", "tr_Cc9Yh3VbNm", 50_000, feeMinor = 915, moneyBackMinor = 20_000),
                    ReportPayment(Instant.parse("2026-11-28T11:15:00Z"), ana, "CARD", "tr_Ee5Rr2TtYy", 206_000, 200_000, 6_000),
                ),
            ledger =
                ReportLedger(
                    moneyBack = listOf(refund),
                    payouts =
                        listOf(
                            ReportPayout(Instant.parse("2026-11-05T05:00:00Z"), 350_921, "stl_jDk30akdN", "trf_a1b2c3", listOf(anaLine, johnLine)),
                            ReportPayout(
                                Instant.parse("2026-11-10T05:00:00Z"),
                                29_052,
                                "stl_Qm82hsLxP",
                                null,
                                listOf(linkLine, refund, line("2026-11-09T07:00:00Z", "movement", null, null, -8, 0), line("2026-11-09T08:00:00Z", "newtype", null, null, 0, 0)),
                            ),
                        ),
                    notPaidOut = listOf(unknown),
                    complete = true,
                ),
        )
    }

    @Test
    fun `the report renders with Croatian letters, payers, booking numbers and the transfers' lines`() {
        val bytes = pdf.render(sample())
        File("build/reports").mkdirs()
        File("build/reports/payments-report-sample.pdf").writeBytes(bytes)

        String(bytes, 0, 5, Charsets.ISO_8859_1) shouldBe "%PDF-"
        val text = text(bytes)
        text shouldContain "Uplate i isplate (Mollie)"
        text shouldContain "01.11.2026. – 30.11.2026."
        // Croatian letters come out as letters (the embedded font has them)
        text shouldContain "Ana Šimić"
        text shouldContain "John Đorđević-Čačić"
        text shouldContain "Ivo Žužić"
        text shouldContain "1441001/2026"
        // 2.11. 14:21 UTC is 15:21 in Croatia
        text shouldContain "02.11.2026. 15:21"
        text shouldContain "1.030,00"
        text shouldContain "rata 1.000,00 + naknada za karticu 30,00"
        text shouldContain "link za plaćanje"
        text shouldContain "rezervacija 1441007/2026"
        text shouldContain "3.509,21"
        text shouldContain "stl_jDk30akdN · trf_a1b2c3"
        text shouldContain "Povrat kupcu"
        text shouldContain "Ostalo kretanje"
        // a type Mollie adds later still shows, under its own name
        text shouldContain "Ostalo (newtype)"
        text shouldContain "Boat4You 1441011/2026"
        text shouldNotContain "Razlika između isplate"
        text shouldNotContain "nisu dostupni"
    }

    @Test
    fun `without Mollie's balance the report says so and still lists the payments`() {
        val text = text(pdf.render(sample().copy(ledger = null)))

        text shouldContain "Ana Šimić"
        text shouldContain "Podaci s Mollie računa trenutno nisu dostupni"
        text shouldNotContain "Isplate na bankovni račun"
    }

    @Test
    fun `an empty period renders, and a transfer that does not match its lines shows the difference`() {
        val empty = PaymentsReport(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), Instant.parse("2026-02-01T08:00:00Z"), emptyList(), ReportLedger(emptyList(), emptyList(), emptyList(), false))
        val emptyText = text(pdf.render(empty))
        emptyText shouldContain "U razdoblju nema zaprimljenih uplata."
        emptyText shouldContain "U razdoblju nema isplata na bankovni račun."
        emptyText shouldContain "sastav najstarije isplate može biti nepotpun"

        val partial =
            empty.copy(
                ledger =
                    ReportLedger(
                        emptyList(),
                        listOf(ReportPayout(Instant.parse("2026-01-10T05:00:00Z"), 4_000, null, null, listOf(line("2026-01-09T10:00:00Z", "payment", "tr_x", null, 5_000, 0)))),
                        emptyList(),
                        true,
                    ),
            )
        val partialText = text(pdf.render(partial))
        partialText shouldContain "Razlika između isplate i stavki"
        partialText shouldContain "-10,00"
    }

    @Test
    fun `money is written the Croatian way`() {
        PaymentsReportPdf.money(0) shouldBe "0,00"
        PaymentsReportPdf.money(5) shouldBe "0,05"
        PaymentsReportPdf.money(123_456_789) shouldBe "1.234.567,89"
        PaymentsReportPdf.money(-5_025) shouldBe "-50,25"
    }
}
