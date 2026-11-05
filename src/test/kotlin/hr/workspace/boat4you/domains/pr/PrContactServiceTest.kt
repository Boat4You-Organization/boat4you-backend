package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppression
import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppressionRepository
import hr.workspace.boat4you.domains.pr.dto.PrContactFilter
import hr.workspace.boat4you.domains.pr.dto.PrContactRequest
import hr.workspace.boat4you.domains.pr.jpa.PrContact
import hr.workspace.boat4you.domains.pr.jpa.PrContactRepository
import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import hr.workspace.boat4you.domains.pr.jpa.PrOutlet
import hr.workspace.boat4you.domains.pr.jpa.PrOutletRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitch
import hr.workspace.boat4you.domains.pr.jpa.PrPitchRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitchTemplate
import hr.workspace.boat4you.domains.pr.service.PrContactService
import hr.workspace.boat4you.domains.pr.service.PrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Outlets, contacts, CSV import, opt-out / bounce and the 24-month retention against the real V9_56 + V9_121 schema. */
class PrContactServiceTest : PrJpaTest() {
    @Autowired private lateinit var service: PrContactService

    @Autowired private lateinit var outlets: PrOutletRepository

    @Autowired private lateinit var contacts: PrContactRepository

    @Autowired private lateinit var pitches: PrPitchRepository

    @Autowired private lateinit var suppressions: EmailSuppressionRepository

    private val header = "market,outlet,tier,person,role,email,email_source_url,desk_contact,recent_article_title,recent_article_url,notes,found_on"

    private fun csv(vararg rows: String) = (listOf(header) + rows).joinToString("\r\n", postfix = "\r\n")

    // Made-up people and addresses in the shape of the real first-wave file (quoted commas and quotes, long desk contacts).
    private val twoAtOneOutlet =
        csv(
            "UK,The Daily Example,1,Jane Testwriter,\"Travel editor (news, features)\",Jane.Testwriter@Example.co.uk ," +
                "https://example.co.uk/authors/jane,newsdesk@example.co.uk (https://example.co.uk/contact),\"Why \"\"cheap\"\" sailing costs more\"," +
                "https://example.co.uk/travel/sailing-costs,\"Prefers data, not adjectives.\",2026-11-04",
            "UK,the daily example,1,Tom Sample,Deputy travel editor,tom.sample@example.co.uk,https://example.co.uk/authors/tom,,,,,2026-11-03",
        )

    @Test
    fun importCreatesOutletsAndContacts() {
        val result = service.importCsv(twoAtOneOutlet)

        result.created shouldBe 2
        result.updated shouldBe 0
        result.skipped shouldBe emptyList()
        val outlet = outlets.findAll().single()
        outlet.name shouldBe "The Daily Example"
        outlet.tier shouldBe 1.toShort()
        outlet.deskContact shouldBe "newsdesk@example.co.uk (https://example.co.uk/contact)"
        val jane = contacts.findAll().single { it.name == "Jane Testwriter" }
        jane.outletId shouldBe outlet.id
        jane.email shouldBe "jane.testwriter@example.co.uk"
        jane.role shouldBe "Travel editor (news, features)"
        jane.market shouldBe "GB"
        jane.status shouldBe PrContactStatus.ACTIVE
        jane.emailSourceUrl shouldBe "https://example.co.uk/authors/jane"
        jane.emailFoundOn shouldBe LocalDate.of(2026, 11, 4)
        jane.recentArticleUrl shouldBe "https://example.co.uk/travel/sailing-costs"
        jane.notes shouldBe "Recent article: Why \"cheap\" sailing costs more\nPrefers data, not adjectives."
        contacts.findAll().single { it.name == "Tom Sample" }.notes shouldBe null
    }

    @Test
    fun importMapsUkToGb() {
        service.importCsv(
            csv(
                "UK,Example Weekly,2,A Person,Writer,a@weekly.example,https://weekly.example/a,,,,,2026-11-04",
                "USA,Example Times,1,B Person,Writer,b@times.example,https://times.example/b,,,,,2026-11-04",
                "New Zealand,Example Herald,2,C Person,Writer,c@herald.example,https://herald.example/c,,,,,2026-11-04",
            ),
        )

        outlets.findAll().associate { it.name to it.market } shouldBe
            mapOf("Example Weekly" to "GB", "Example Times" to "US", "Example Herald" to "NZ")
        contacts.findAll().associate { it.email to it.market } shouldBe
            mapOf("a@weekly.example" to "GB", "b@times.example" to "US", "c@herald.example" to "NZ")
    }

    @Test
    fun importSkipsRowWithoutEmail() {
        val longDesk = "newsdesk@desk.example (https://desk.example/" + "x".repeat(400) + ")"
        val result =
            service.importCsv(
                csv(
                    "Australia,Desk Only Daily,1,No Address,Travel editor,,,\"$longDesk\",,,Author page shows no email.,2026-11-04",
                    "Croatia/Greece (EN),Somewhere Sailing,3,D Person,Editor,d@sailing.example,https://sailing.example/d,,,,,2026-11-04",
                ),
            )

        result.created shouldBe 0
        result.skipped shouldHaveSize 2
        result.skipped[0] shouldContain "NO_EMAIL"
        result.skipped[1] shouldContain "MARKET_UNKNOWN: Croatia/Greece (EN)"
        contacts.count() shouldBe 0
        // R7: the desk-only row still keeps its outlet; R17: an unknown market creates nothing at all.
        val outlet = outlets.findAll().single()
        outlet.name shouldBe "Desk Only Daily"
        outlet.market shouldBe "AU"
        outlet.deskContact!!.length shouldBe 320
        outlet.deskContact!! shouldStartWith "newsdesk@desk.example"
    }

    @Test
    fun importIsIdempotentOnSecondRun() {
        service.importCsv(twoAtOneOutlet).created shouldBe 2
        val again = service.importCsv(twoAtOneOutlet)

        again.created shouldBe 0
        again.updated shouldBe 0
        outlets.count() shouldBe 1
        contacts.count() shouldBe 2
    }

    @Test
    fun importNeverChangesStatusAndOptsOutSuppressedNewContacts() {
        service.importCsv(twoAtOneOutlet)
        val tom = contacts.findAll().single { it.name == "Tom Sample" }
        service.optOut(tom.id!!)
        suppressions.save(
            EmailSuppression().apply {
                email = "new.person@example.co.uk"
                reason = "UNSUBSCRIBE_LINK"
            },
        )

        val result =
            service.importCsv(
                csv(
                    "UK,The Daily Example,1,Tom Sample,Travel editor,TOM.sample@example.co.uk,https://example.co.uk/authors/tom,,,,Covers sailing.,2026-11-03",
                    "UK,The Daily Example,1,New Person,Writer,new.person@example.co.uk,https://example.co.uk/authors/new,,,,,2026-11-04",
                ),
            )

        result.created shouldBe 1
        result.updated shouldBe 1
        contacts.findById(tom.id!!).get().let {
            it.status shouldBe PrContactStatus.OPTED_OUT
            // R41: a field with a value is never overwritten, an empty one is filled.
            it.role shouldBe "Deputy travel editor"
            it.notes shouldBe "Covers sailing."
        }
        contacts.findAll().single { it.name == "New Person" }.status shouldBe PrContactStatus.OPTED_OUT
    }

    @Test
    fun reimportNeverOverwritesFieldsSetByHand() {
        service.importCsv(twoAtOneOutlet)
        val jane = contacts.findAll().single { it.name == "Jane Testwriter" }
        service.updateContact(jane.id!!, PrContactRequest(market = "DE", notes = "Mario: phoned her, prefers German data"))

        val again =
            service.importCsv(
                csv(
                    "UK,The Daily Example,1,Jane Other,Editor,jane.testwriter@example.co.uk,https://other.example/jane,,Other title," +
                        "https://other.example/a,Other notes,2026-10-01",
                ),
            )

        again.created shouldBe 0
        again.updated shouldBe 0
        contacts.findById(jane.id!!).get().let {
            it.market shouldBe "DE"
            it.notes shouldBe "Mario: phoned her, prefers German data"
            it.name shouldBe "Jane Testwriter"
            it.role shouldBe "Travel editor (news, features)"
            it.emailSourceUrl shouldBe "https://example.co.uk/authors/jane"
            it.emailFoundOn shouldBe LocalDate.of(2026, 11, 4)
            it.recentArticleUrl shouldBe "https://example.co.uk/travel/sailing-costs"
            it.status shouldBe PrContactStatus.ACTIVE
        }
    }

    @Test
    fun doNotContactAppliesToEveryContactWithSameEmail() {
        val first = service.createContact(PrContactRequest(outletId = outlet("Example Gazette").id, email = "ed@x.com"))
        val second = service.createContact(PrContactRequest(outletId = outlet("Example Courier").id, email = "ED@x.com"))
        val other = service.createContact(PrContactRequest(outletId = outlet("Example Herald").id, email = "someone.else@x.com"))

        service.updateContact(first.id, PrContactRequest(status = PrContactStatus.DO_NOT_CONTACT)).status shouldBe PrContactStatus.DO_NOT_CONTACT

        contacts.findById(second.id).get().status shouldBe PrContactStatus.DO_NOT_CONTACT
        contacts.findById(other.id).get().status shouldBe PrContactStatus.ACTIVE
        // Not an opt-out: no suppression row, and the person can be set back to ACTIVE by hand.
        suppressions.count() shouldBe 0
        service.updateContact(second.id, PrContactRequest(status = PrContactStatus.ACTIVE)).status shouldBe PrContactStatus.ACTIVE
    }

    @Test
    fun optOutBlocksEveryContactWithSameEmail() {
        val a = outlet("Example Gazette")
        val b = outlet("Example Courier")
        val first = service.createContact(PrContactRequest(outletId = a.id, email = "ED@x.com"))
        val second = service.createContact(PrContactRequest(outletId = b.id, email = "ed@x.com "))
        val other = service.createContact(PrContactRequest(outletId = b.id, email = "someone.else@x.com"))
        first.email shouldBe "ed@x.com"
        second.email shouldBe "ed@x.com"

        service.optOut(first.id)
        service.optOut(second.id) // idempotent: no second suppression row

        contacts.findById(first.id).get().status shouldBe PrContactStatus.OPTED_OUT
        contacts.findById(second.id).get().status shouldBe PrContactStatus.OPTED_OUT
        contacts.findById(other.id).get().status shouldBe PrContactStatus.ACTIVE
        suppressions.findAll().map { it.email to it.reason } shouldBe listOf("ed@x.com" to "MANUAL")
    }

    @Test
    fun bounceSuppressesWithReasonBounceAndOptedOutStatusIsFinal() {
        val contact = service.createContact(PrContactRequest(outletId = outlet("Example Post").id, email = "bounce@example.com"))

        service.markBounced(contact.id).status shouldBe PrContactStatus.OPTED_OUT

        suppressions.findByEmailIgnoreCase("bounce@example.com")!!.reason shouldBe "BOUNCE"
        val e = shouldThrow<PrException> { service.updateContact(contact.id, PrContactRequest(status = PrContactStatus.ACTIVE)) }
        e.status shouldBe HttpStatus.CONFLICT
        e.code shouldBe "CONTACT_OPTED_OUT"
        val active = service.createContact(PrContactRequest(outletId = outlet("Example Mail").id, email = "fine@example.com"))
        shouldThrow<PrException> { service.updateContact(active.id, PrContactRequest(status = PrContactStatus.OPTED_OUT)) }.code shouldBe
            "STATUS_NOT_ALLOWED"
        service.updateContact(active.id, PrContactRequest(status = PrContactStatus.CONSENTED, market = "UK")).let {
            it.status shouldBe PrContactStatus.CONSENTED
            it.market shouldBe "GB"
        }
    }

    @Test
    fun listContactsFiltersSearchesAndPagesOnTheDatabase() {
        val gazette = outlet("Example Gazette")
        val tribune =
            outlets.save(
                PrOutlet().apply {
                    name = "Harbor Tribune"
                    market = "US"
                    tier = 1
                },
            )
        val anna = service.createContact(PrContactRequest(outletId = gazette.id, email = "anna_reed@gazette.example", name = "Anna Reed"))
        val bob = service.createContact(PrContactRequest(outletId = gazette.id, email = "bob@gazette.example", name = "Bob Stone", status = PrContactStatus.CONSENTED))
        val carl = service.createContact(PrContactRequest(outletId = tribune.id, email = "carl@tribune.example", name = "Carl 100% Sails"))
        // Would match "anna_reed" if _ were a LIKE wildcard.
        val annax = service.createContact(PrContactRequest(outletId = tribune.id, email = "annaxreed@tribune.example"))

        fun ids(filter: PrContactFilter) = service.listContacts(filter).content.map { it.id }

        ids(PrContactFilter(market = "UK")) shouldContainExactlyInAnyOrder listOf(anna.id, bob.id)
        ids(PrContactFilter(status = PrContactStatus.CONSENTED)) shouldBe listOf(bob.id)
        ids(PrContactFilter(q = "ANNA_REED")) shouldBe listOf(anna.id)
        ids(PrContactFilter(q = "stone")) shouldBe listOf(bob.id)
        ids(PrContactFilter(q = "harbor")) shouldContainExactlyInAnyOrder listOf(carl.id, annax.id)
        ids(PrContactFilter(q = "%")) shouldBe listOf(carl.id)
        ids(PrContactFilter(market = "US", q = "anna")) shouldBe listOf(annax.id)

        val first = service.listContacts(PrContactFilter(page = 0, size = 1))
        first.content.map { it.id } shouldBe listOf(annax.id)
        first.totalElements shouldBe 4
        first.content.single().outletName shouldBe "Harbor Tribune"
        service.listContacts(PrContactFilter(page = 1, size = 1)).content.map { it.id } shouldBe listOf(carl.id)
    }

    @Test
    fun purgeDeletesOnlyContactsInactive24Months() {
        val now = Instant.parse("2026-11-05T10:00:00Z")

        fun monthsAgo(m: Long) = now.atZone(ZoneOffset.UTC).minusMonths(m).toInstant()
        val outlet = outlet("Example Chronicle")
        val neverContacted = contact(outlet, "old@example.com", createdAt = monthsAgo(25))
        val contactedLongAgo = contact(outlet, "stale@example.com", createdAt = monthsAgo(40), lastContactedAt = monthsAgo(25))
        val contactedRecently = contact(outlet, "recent@example.com", createdAt = monthsAgo(40), lastContactedAt = monthsAgo(23))
        val newContact = contact(outlet, "new@example.com", createdAt = monthsAgo(1))
        // The purged contact has a main pitch and a follow-up pointing at it (parent_pitch_id has no ON DELETE rule).
        val main = pitches.save(pitch(neverContacted))
        pitches.save(pitch(neverContacted).apply { parentPitchId = main.id })
        pitches.save(pitch(contactedRecently))
        service.optOut(neverContacted.id!!)

        service.purgeInactive(now) shouldBe 2

        contacts.findAll().map { it.id } shouldContainExactlyInAnyOrder listOf(contactedRecently.id, newContact.id)
        contacts.existsById(contactedLongAgo.id!!) shouldBe false
        pitches.findAll().map { it.contactId } shouldBe listOf(contactedRecently.id)
        suppressions.existsByEmailIgnoreCase("old@example.com") shouldBe true
        outlets.count() shouldBe 1
    }

    private fun outlet(name: String): PrOutlet =
        outlets.save(
            PrOutlet().apply {
                this.name = name
                market = "GB"
                tier = 2
            },
        )

    private fun contact(
        outlet: PrOutlet,
        email: String,
        createdAt: Instant,
        lastContactedAt: Instant? = null,
    ): PrContact =
        contacts.save(
            PrContact().apply {
                outletId = outlet.id
                this.email = email
                market = "GB"
                this.createdAt = createdAt
                this.lastContactedAt = lastContactedAt
            },
        )

    private fun pitch(contact: PrContact) =
        PrPitch().apply {
            contactId = contact.id
            template = PrPitchTemplate.DATA_PITCH
            subject = "Sailing costs 2027"
            body = "Hello"
        }
}
