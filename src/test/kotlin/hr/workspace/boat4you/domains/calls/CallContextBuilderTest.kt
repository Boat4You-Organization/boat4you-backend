package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.service.CallContextBuilder
import hr.workspace.boat4you.domains.calls.service.CallsSettings
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** What Nika is told about the inquiry (design §6.5): never the agency or owner, prices only in euros. */
class CallContextBuilderTest {
    private val s = CallsSettings.Snapshot(transferNumber = "+385981234567")
    private val friday = Instant.parse("2026-11-06T10:00:00Z")
    private val saturday = Instant.parse("2026-11-07T10:00:00Z")

    private fun inquiry(site: String? = "italy") =
        Inquiry().apply {
            id = 7
            name = "Giulia"
            surname = "Rossi"
            this.site = site
            locale = "it"
            phoneE164 = "+393331234567"
            dateFrom = LocalDate.of(2027, 6, 12)
            dateTo = LocalDate.of(2027, 6, 19)
            message = "Siamo 8 persone"
            details = mapOf("people" to 8, "cabins" to 4, "budgetEur" to "12000", "weekPriceEur" to 4066, "crew" to "skipper")
            yacht =
                Yacht().apply {
                    name = "Anthea"
                    agency = Agency().apply { name = "Odisej Ltd" }
                    location = Location().apply { name = "Marina Kaštela" }
                    model =
                        Model().apply {
                            name = "Lagoon 46"
                            manufacturer = Manufacturer().apply { name = "Lagoon" }
                        }
                }
            offerNote = "Lagoon 46, 12.–19.6., 11.900 €"
        }

    private fun call(kind: CallKind = CallKind.CONFIRMATION) =
        InquiryCall().apply {
            this.kind = kind
            language = "it"
        }

    @Test
    fun `the site is named as Nika says it`() {
        CallContextBuilder.variables(inquiry(), call(), friday, s)["site_name"] shouldBe "Catamaran Charter Italy"
        CallContextBuilder.variables(inquiry(site = null), call(), friday, s)["site_name"] shouldBe "Boat4You"
        CallContextBuilder.variables(inquiry(), call(), friday, s)["site_domain"] shouldBe "www.catamarancharteritaly.com"
    }

    @Test
    fun `the summary carries boat, dates, base and wishes but never the agency`() {
        val v = CallContextBuilder.variables(inquiry(), call(), friday, s)
        val summary = v.getValue("inquiry_summary")
        summary shouldContain "Lagoon 46 Anthea"
        summary shouldContain "2027-06-12"
        summary shouldContain "Marina Kaštela"
        summary shouldContain "people: 8"
        summary shouldNotContain "Odisej"
        v.values.joinToString(" ") shouldNotContain "Odisej"
    }

    @Test
    fun `the week price is a bare euro number, the client is named, the language and kind are set`() {
        val v = CallContextBuilder.variables(inquiry(), call(CallKind.FOLLOW_UP), friday, s)
        v["week_price_eur"] shouldBe "4066"
        v["client_name"] shouldBe "Giulia Rossi"
        v["language"] shouldBe "it"
        v["inquiry_kind"] shouldBe "follow_up"
        v["offer_summary"] shouldBe "Lagoon 46, 12.–19.6., 11.900 €"
        v["offer_email"] shouldBe ""
        v["client_timezone"] shouldBe "Europe/Rome"
    }

    @Test
    fun `Nika is told the language she will actually speak, by the country of the number`() {
        val english = call().apply { language = "en" }
        CallContextBuilder.variables(inquiry(), english, friday, s)["language"] shouldBe "it" // Italian number, English form
        val kuwait = inquiry().apply { phoneE164 = "+96550012345" }
        CallContextBuilder.variables(kuwait, call(), friday, s)["language"] shouldBe "en"
        val swiss = inquiry().apply { phoneE164 = "+41791234567" }
        CallContextBuilder.variables(swiss, call().apply { language = "fr-CH" }, friday, s)["language"] shouldBe "fr"
        CallContextBuilder.variables(swiss, call().apply { language = "en" }, friday, s)["language"] shouldBe "de"
        val noNumber = inquiry().apply { phoneE164 = null }
        CallContextBuilder.variables(noNumber, call().apply { language = "pl" }, friday, s)["language"] shouldBe "pl"
        CallContextBuilder.variables(noNumber.apply { locale = null }, call().apply { language = null }, friday, s)["language"] shouldBe "en"
    }

    @Test
    fun `Nika gets the e-mail address the inquiry came from, to confirm it`() {
        CallContextBuilder.variables(inquiry().apply { email = " giulia@example.com " }, call(), friday, s)["client_email"] shouldBe "giulia@example.com"
        CallContextBuilder.variables(inquiry().apply { email = null }, call(), friday, s)["client_email"] shouldBe ""
    }

    @Test
    fun `transfer is allowed on a weekday morning and not on Saturday`() {
        CallContextBuilder.variables(inquiry(), call(), friday, s)["transfer_allowed"] shouldBe "true"
        CallContextBuilder.variables(inquiry(), call(), saturday, s)["transfer_allowed"] shouldBe "false"
        CallContextBuilder.variables(inquiry(), call(), friday, s)["transfer_hours_text"] shouldBe "Monday–Friday 09:00–16:00 Croatian time"
    }
}
