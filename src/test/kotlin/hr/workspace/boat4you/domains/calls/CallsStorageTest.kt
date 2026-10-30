package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.jpa.CallWebhookEventRepository
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AI call center storage (V9_112 inquiry columns, V9_113 inquiry_call + call_webhook_event) through the real Hibernate
 * mappings: `validateMappedObjects` proves the column types match the migrations (V9_94 lesson).
 */
class CallsStorageTest : CallsJpaTest() {
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired private lateinit var events: CallWebhookEventRepository

    private fun inquiryId(): Long =
        jdbc.queryForObject(
            "INSERT INTO inquiry (yacht_id, created_at, email, phone, status) VALUES (NULL, now(), 'a@b.hr', '+385 91 234 5678', 'NEW') RETURNING id",
            Long::class.java,
        )!!

    private fun call(
        inquiryId: Long,
        status: CallStatus = CallStatus.SCHEDULED,
        at: Instant = Instant.parse("2026-11-03T09:30:00Z"),
    ) = calls.save(
        InquiryCall().apply {
            this.inquiryId = inquiryId
            kind = CallKind.CONFIRMATION
            attempt = 1
            this.status = status
            scheduledAt = at
            toNumber = "+385912345678"
            language = "hr"
        },
    )

    @Test
    fun `Hibernate validates the V9_112 and V9_113 mappings`() {
        entityManagerFactory.unwrap(SessionFactory::class.java).schemaManager.validateMappedObjects()
    }

    @Test
    fun `the inquiry call columns default to no consent and callable false`() {
        val id = inquiryId()
        val row = jdbc.queryForMap("SELECT contact_consent, do_not_call, phone_e164 FROM inquiry WHERE id = ?", id)
        row["contact_consent"] shouldBe false
        row["do_not_call"] shouldBe false
        row["phone_e164"].shouldBeNull()
    }

    @Test
    fun `a call row round-trips through the entity with Instant and JSON fields`() {
        val saved =
            call(inquiryId()).apply {
                data = mapOf("people" to 8, "boat_type" to "catamaran")
                transcript = listOf(mapOf("role" to "agent", "message" to "Dobar dan", "time_in_call_secs" to 0))
                quality = mapOf("ai_disclosed" to "success")
                startedAt = Instant.parse("2026-11-03T09:30:05Z")
            }
        calls.save(saved)
        val read = calls.findById(saved.id!!).get()
        read.data shouldBe mapOf("people" to 8, "boat_type" to "catamaran")
        read.transcript!!.first()["message"] shouldBe "Dobar dan"
        read.quality shouldBe mapOf("ai_disclosed" to "success")
        read.startedAt shouldBe Instant.parse("2026-11-03T09:30:05Z")
        read.scheduledAt shouldBe Instant.parse("2026-11-03T09:30:00Z")
    }

    @Test
    fun `deleting an inquiry deletes its calls`() {
        val id = inquiryId()
        call(id)
        jdbc.update("DELETE FROM inquiry WHERE id = ?", id)
        calls.count() shouldBe 0
    }

    @Test
    fun `a second webhook event with the same conversation and type is ignored`() {
        val tx = TransactionTemplate(txManager)
        val first = tx.execute { events.insertIfAbsent("conv-1", "post_call_transcription", """{"type":"post_call_transcription"}""") }
        val second = tx.execute { events.insertIfAbsent("conv-1", "post_call_transcription", """{"type":"post_call_transcription"}""") }
        first.shouldNotBeNull()
        second.shouldBeNull()
        events.count() shouldBe 1
    }

    @Test
    fun `claimDue skips rows another transaction holds`() {
        val id = inquiryId()
        val now = Instant.parse("2026-11-03T10:00:00Z")
        val a = call(id).id!!
        val b = call(id).id!!
        call(id, at = Instant.parse("2026-11-04T09:30:00Z")) // not due
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            CompletableFuture.supplyAsync {
                TransactionTemplate(txManager).execute {
                    val claimed = calls.claimDue(now, 1)
                    locked.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    claimed
                }!!
            }
        locked.await(10, TimeUnit.SECONDS)
        val second = TransactionTemplate(txManager).execute { calls.claimDue(now, 5) }!!
        release.countDown()
        val first = holder.get(10, TimeUnit.SECONDS)
        first.size shouldBe 1
        second shouldNotContain first.single()
        (first + second) shouldContainExactlyInAnyOrder listOf(a, b)
    }
}
