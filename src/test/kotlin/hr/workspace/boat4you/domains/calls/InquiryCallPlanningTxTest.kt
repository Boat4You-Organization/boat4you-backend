package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.branding.BrandResolver
import hr.workspace.boat4you.domains.calls.service.CallPlanner
import hr.workspace.boat4you.domains.calls.service.CallsSettings
import hr.workspace.boat4you.domains.catalouge.dto.InquiryDto
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.services.InquiryEmailService
import hr.workspace.boat4you.domains.catalouge.services.InquiryMutationService
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The inquiry path through real Spring transactions: the call is planned after the inquiry commits, in the planner's own
 * transaction, and a planner failure never costs the inquiry or its e-mail (which EmailService defers to the same commit).
 */
@Import(InquiryMutationService::class, CallPlanner::class, CallsSettings::class)
class InquiryCallPlanningTxTest : CallsJpaTest() {
    @MockitoBean private lateinit var inquiryEmails: InquiryEmailService

    @MockitoBean private lateinit var brandResolver: BrandResolver

    @Autowired private lateinit var service: InquiryMutationService

    private val mailed = AtomicBoolean()

    @BeforeEach
    fun deferredMail() {
        mailed.set(false)
        // Like EmailService.sendEmail: the SMTP submit runs in the inquiry transaction's afterCommit.
        doAnswer {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = mailed.set(true)
                },
            )
            null
        }.`when`(inquiryEmails).sendNewInquiryNotification(any(Inquiry::class.java) ?: Inquiry(), any(), anyBoolean())
    }

    private fun dto() =
        InquiryDto(
            yachtId = null,
            dateFrom = null,
            dateTo = null,
            name = "Ana",
            surname = "Kovač",
            email = "ana-${System.nanoTime()}@example.hr",
            phone = "+385 91 234 5678",
            message = "Pozdrav",
            site = "cc",
            locale = "hr",
            contactConsent = true,
        )

    @Test
    fun `a committed inquiry gets its confirmation call from the planner's own transaction`() {
        service.createNewInquiry(dto())
        val id = jdbc.queryForObject("SELECT id FROM inquiry", Long::class.java)
        jdbc.queryForObject("SELECT status FROM inquiry_call WHERE inquiry_id = ?", String::class.java, id) shouldBe "SCHEDULED"
        mailed.get() shouldBe true
    }

    @Test
    fun `a planner failure after the commit keeps the inquiry and its deferred e-mail`() {
        jdbc.execute("CREATE FUNCTION calls_test_no_insert() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'planner down'; END \$\$")
        jdbc.execute("CREATE TRIGGER calls_test_no_insert BEFORE INSERT ON inquiry_call FOR EACH ROW EXECUTE FUNCTION calls_test_no_insert()")
        try {
            shouldNotThrowAny { service.createNewInquiry(dto()) }
        } finally {
            jdbc.execute("DROP TRIGGER calls_test_no_insert ON inquiry_call")
            jdbc.execute("DROP FUNCTION calls_test_no_insert()")
        }
        jdbc.queryForObject("SELECT count(*) FROM inquiry", Long::class.java) shouldBe 1L
        jdbc.queryForObject("SELECT count(*) FROM inquiry_call", Long::class.java) shouldBe 0L
        mailed.get() shouldBe true
    }
}
