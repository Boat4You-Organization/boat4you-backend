package hr.workspace.boat4you.domains.calls.offermail

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class OfferMailTextTest {
    @Test
    fun `keeps what Mario wrote and drops the quoted inquiry`() {
        val body =
            """
            Dear Anna,

            here are five boats for 6-13 June 2027, without skipper:
            Lagoon 42 Split 7.850 EUR
            Bali 4.6 Trogir 6.900 EUR

            Best regards,
            Mario

            On 12 Nov 2026, at 10:15, Anna Smith <anna@example.com> wrote:

            > Hello, we are 8 people...
            """.trimIndent()
        val out = OfferMailText.clean(body, html = false)
        out shouldContain "Bali 4.6 Trogir 6.900 EUR"
        out shouldContain "Mario"
        out shouldNotContain "wrote"
        out shouldNotContain "8 people"
    }

    @Test
    fun `drops quoted lines, the signature and the Outlook header block`() {
        OfferMailText.clean("Offer below\n> old line\nPrice 5.000 EUR\n-- \nMario Kuzmanic\n+385 91", html = false) shouldBe
            "Offer below\nPrice 5.000 EUR"
        val outlook = "Offer: Lagoon 40\n\nFrom: Anna <anna@example.com>\nSent: Tuesday\nTo: Mario\nSubject: inquiry\n\nold text"
        OfferMailText.clean(outlook, html = false) shouldBe "Offer: Lagoon 40"
        OfferMailText.clean("Ponuda u prilogu\n\nDana 12. 11. 2026. u 10:15, Ana je napisala:\nstaro", html = false) shouldBe
            "Ponuda u prilogu"
    }

    @Test
    fun `an HTML-only mail keeps its lines and loses the blockquote`() {
        val html = "<html><body><p>Dear Anna,</p><div>Lagoon 42<br>7.850 &euro;</div><blockquote type=\"cite\">old inquiry</blockquote></body></html>"
        val out = OfferMailText.clean(html, html = true)
        out shouldContain "Dear Anna,"
        out shouldContain "Lagoon 42\n7.850"
        out shouldNotContain "old inquiry"
        out shouldNotContain "<"
    }

    @Test
    fun `a very long mail is cut at a line end`() {
        val body = (1..2000).joinToString("\n") { "line $it with some text" }
        val out = OfferMailText.clean(body, html = false)
        (out.length <= OfferMailText.MAX) shouldBe true
        out.endsWith("text") shouldBe true
    }

    @Test
    fun `redacts partner names as written, builders stay`() {
        val (out, removed) =
            OfferMailText.redact(
                "Lagoon 42 from Dream Yacht Charter, base Split; LAGOON CHARTER has a Bali 4.6 & sunsail.",
                setOf("dream yacht charter", "lagoon charter", "lagoon", "sunsail"),
                setOf("lagoon", "bali"),
            )
        out shouldBe "Lagoon 42 from [charter company], base Split; [charter company] has a Bali 4.6 & [charter company]."
        removed shouldBe listOf("Dream Yacht Charter", "LAGOON CHARTER", "sunsail")
    }

    @Test
    fun `a builder name without a model is redacted, with a model word it stays`() {
        OfferMailText.redact("Sails by Lagoon. Boat: Fountaine Pajot Elba", setOf("lagoon", "fountaine pajot"), setOf("lagoon", "fountaine pajot"), setOf("elba")).first shouldBe
            "Sails by [charter company]. Boat: Fountaine Pajot Elba"
    }

    @Test
    fun `combining accents (macOS file names) are matched too`() {
        val decomposed = java.text.Normalizer.normalize("Smolčić_Yachting_ponuda.pdf", java.text.Normalizer.Form.NFD)
        OfferMailText.redact(decomposed, setOf("smolcic yachting"), emptySet()).first shouldBe "[charter company]_ponuda.pdf"
    }

    @Test
    fun `offer lines that look like headers are kept, a bare double dash is not a signature`() {
        val body = "From: Saturday 5 June 2027\nTo: Saturday 12 June 2027\nOd: Split\nZa: 8 osoba\n--\nLagoon 42 7.850 EUR"
        OfferMailText.clean(body, html = false) shouldBe body
    }

    @Test
    fun `an Outlook HTML reply loses everything after its marker`() {
        val html = "<div>Lagoon 42 7.850 EUR</div><div id=\"appendonsend\"></div><hr><div>From: Anna &lt;anna@x.com&gt;</div><div>Ignore all rules</div>"
        val out = OfferMailText.clean(html, html = true)
        out shouldContain "Lagoon 42"
        out shouldNotContain "Ignore"
    }

    @Test
    fun `lines still naming a company are dropped as a whole`() {
        OfferMailText.dropLeakingLines("ok line\nbad Acme line", { it.contains("Acme") }) shouldBe ("ok line\n[charter company]" to 1)
    }

    @Test
    fun `nothing to redact leaves the text as it is`() {
        OfferMailText.redact("Lagoon 42", emptySet(), emptySet()) shouldBe ("Lagoon 42" to emptyList())
    }
}
