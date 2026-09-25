package hr.workspace.boat4you.domains.review

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.review.dto.ReviewSubmitRequest
import hr.workspace.boat4you.domains.review.exceptions.ReviewEditWindowClosedException
import hr.workspace.boat4you.domains.review.exceptions.ReviewLinkInvalidException
import hr.workspace.boat4you.domains.review.exceptions.ReviewNotFoundException
import hr.workspace.boat4you.domains.review.service.ReviewDao
import hr.workspace.boat4you.domains.review.service.ReviewInvitationMailer
import hr.workspace.boat4you.domains.review.service.ReviewInvitationService
import hr.workspace.boat4you.domains.review.service.ReviewService
import hr.workspace.boat4you.domains.review.service.ReviewTokens
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The review collection against a real PostgreSQL: the V9_62 migration (run twice — idempotent), the eligibility
 * queries, the once-only claim, the daily sweep, and the magic-link form (create / edit / 24 h window / expiry) plus
 * admin moderation and GDPR anonymisation. Same approach as CharterFactsComputeServiceTest: no Spring context or
 * Flyway, a minimal schema with exactly the columns the review code reads.
 *
 * Fixture (times relative to the database clock):
 *  BOOKING — 1 eligible (paid 1 h ago, German customer); 2 fictitious; 3 unpaid option; 4 first payment 10 days ago
 *  (second one 1 h ago); 5 GDPR-deleted user; 6 opted-out user; 7 swap replacement of 1 (paid 1 h ago);
 *  8 cancelled original with a BOOKING request already -> 9 its replacement, 11 the replacement's replacement;
 *  12 cancelled but paid.
 *  YACHT — 21 ended 3 days ago (eligible); 22 ended 2 days ago; 23 ended 20 days ago; 24 ended 5 days ago but
 *  unpaid; 25 ended 5 days ago but cancelled; 26 ended 14 days ago (eligible, lookback boundary).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReviewCollectionIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        val MINIMAL_SCHEMA =
            """
            CREATE TABLE country (id int PRIMARY KEY, name varchar(100));
            CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255) NOT NULL, country_id int);
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name varchar(255) NOT NULL);
            CREATE TABLE model (id bigint PRIMARY KEY, name varchar(255) NOT NULL, manufacturer_id bigint);
            CREATE TABLE yacht (id bigint PRIMARY KEY, name varchar(255) NOT NULL, model_id bigint, main_image_id bigint);
            CREATE TABLE users (id bigint PRIMARY KEY, name varchar(255) NOT NULL, surname varchar(255) NOT NULL,
                                email varchar(255) NOT NULL, language varchar(10), country varchar(100), deleted_at timestamp,
                                marketing_opt_out boolean NOT NULL DEFAULT false);
            CREATE TABLE reservation_flow (id bigint PRIMARY KEY, yacht_id bigint NOT NULL, user_id bigint NOT NULL,
                                           email varchar(255) NOT NULL, name varchar(255), surname varchar(255),
                                           previous_flow_id bigint);
            CREATE TABLE reservation (id bigint PRIMARY KEY, reservation_flow_id bigint NOT NULL, date_from timestamp NOT NULL,
                                      date_to timestamp NOT NULL, reservation_number varchar(32), external_id bigint,
                                      external_status varchar(30), sys_status varchar(31) NOT NULL, location_from bigint NOT NULL);
            CREATE TABLE reservation_payment_phase (id bigserial PRIMARY KEY, reservation_flow_id bigint NOT NULL, paid_on timestamp);
            """.trimIndent()
    }

    private data class Sent(
        val kind: ReviewKind,
        val reservationId: Long,
        val locale: String,
        val url: String,
    )

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var dao: ReviewDao
    private lateinit var invitations: ReviewInvitationService
    private lateinit var reviews: ReviewService
    private val sent = mutableListOf<Sent>()

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 3
            }
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute(MINIMAL_SCHEMA)
        val migration = ClassPathResource("db/migration/V9_62__reservation_reviews.sql").inputStream.bufferedReader().readText()
        // Twice, in a transaction like Flyway (it uses SET LOCAL): the migration must be re-runnable.
        jdbc.execute("BEGIN; $migration; COMMIT;")
        jdbc.execute("BEGIN; $migration; COMMIT;")

        dao = ReviewDao(jdbc)
        val mailer = ReviewInvitationMailer { kind, context, locale, url -> sent += Sent(kind, context.reservationId, locale, url) }
        invitations = ReviewInvitationService(dao, mailer, DataSourceTransactionManager(dataSource), "https://www.boat4you.com", true)
        reviews = ReviewService(dao)
        seed()
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    private fun seed() {
        jdbc.execute(
            """
            INSERT INTO country VALUES (1, 'Croatia');
            INSERT INTO location VALUES (10, 'ACI Marina Split', 1);
            INSERT INTO manufacturer VALUES (1, 'Bavaria');
            INSERT INTO model VALUES (1, 'Bavaria Cruiser 46', 1);
            INSERT INTO yacht VALUES (100, 'Tria', 1, 555);
            INSERT INTO users (id, name, surname, email, language, country) VALUES
                (1, 'Ana', 'Horvat', 'ana@example.com', 'DE', 'Germany'),
                (2, 'Deleted', 'User', 'deleted-2@boat4you-deleted.invalid', 'EN', NULL),
                (3, 'Opted', 'Out', 'optout@example.com', 'EN', NULL);
            UPDATE users SET deleted_at = LOCALTIMESTAMP - interval '1 day' WHERE id = 2;
            UPDATE users SET marketing_opt_out = true WHERE id = 3;
            """.trimIndent(),
        )
        // id, user, previous flow
        val flows =
            listOf(
                Triple(1L, 1L, null), Triple(2L, 1L, null), Triple(3L, 1L, null), Triple(4L, 1L, null), Triple(5L, 2L, null),
                Triple(6L, 3L, null), Triple(7L, 1L, 1L), Triple(8L, 1L, null), Triple(9L, 1L, 8L), Triple(11L, 1L, 9L),
                Triple(12L, 1L, null), Triple(21L, 1L, null), Triple(22L, 1L, null), Triple(23L, 1L, null),
                Triple(24L, 1L, null), Triple(25L, 1L, null), Triple(26L, 1L, null),
            )
        flows.forEach { (id, user, previous) ->
            jdbc.update(
                "INSERT INTO reservation_flow VALUES (?, 100, ?, (SELECT email FROM users WHERE id = ?), 'Ana', 'Horvat', ?)",
                id,
                user,
                user,
                previous,
            )
        }
        val future = "LOCALTIMESTAMP + interval '60 days'"
        fun reservation(
            id: Long,
            sysStatus: String = "RESERVATION",
            dateTo: String = future,
            externalId: Long? = 9000 + id,
            externalStatus: String? = "RESERVATION",
        ) = jdbc.update(
            """
            INSERT INTO reservation VALUES (?, ?, ($dateTo) - interval '7 days', $dateTo, ?, ?, ?, ?, 10)
            """.trimIndent(),
            id,
            id,
            "$id/2026",
            externalId,
            externalStatus,
            sysStatus,
        )
        fun paid(
            flowId: Long,
            ago: String,
        ) = jdbc.update("INSERT INTO reservation_payment_phase (reservation_flow_id, paid_on) VALUES (?, LOCALTIMESTAMP - interval '$ago')", flowId)

        reservation(1)
        paid(1, "1 hour")
        reservation(2, externalId = null, externalStatus = "FICTITIOUS")
        paid(2, "1 hour")
        reservation(3, sysStatus = "OPTION")
        jdbc.update("INSERT INTO reservation_payment_phase (reservation_flow_id, paid_on) VALUES (3, NULL)")
        reservation(4)
        paid(4, "10 days")
        paid(4, "1 hour")
        reservation(5)
        paid(5, "1 hour")
        reservation(6)
        paid(6, "1 hour")
        reservation(7)
        paid(7, "1 hour")
        reservation(8, sysStatus = "CANCELLED")
        paid(8, "30 days")
        jdbc.update(
            "INSERT INTO review_request (reservation_id, kind, token_hash, expires_at) VALUES (8, 'BOOKING', ?, now() + interval '30 days')",
            ReviewTokens.hash(ReviewTokens.generate()),
        )
        reservation(9)
        paid(9, "1 hour")
        reservation(11)
        paid(11, "1 hour")
        reservation(12, sysStatus = "CANCELLED")
        paid(12, "1 hour")

        fun ended(daysAgo: Int) = "(CURRENT_DATE - $daysAgo) + time '09:00'"
        reservation(21, dateTo = ended(3))
        paid(21, "60 days")
        reservation(22, dateTo = ended(2))
        paid(22, "60 days")
        reservation(23, dateTo = ended(20))
        paid(23, "60 days")
        reservation(24, dateTo = ended(5))
        reservation(25, sysStatus = "CANCELLED", dateTo = ended(5))
        paid(25, "60 days")
        reservation(26, dateTo = ended(14))
        paid(26, "60 days")
    }

    private fun token(sentMail: Sent) = sentMail.url.substringAfterLast('/')

    @Test
    @Order(1)
    fun `eligibility - only real, paid, confirmed, reachable bookings of the right moment`() {
        dao.bookingCandidateIds(100) shouldContainExactly listOf(1L, 7L)
        dao.bookingCandidateIds(100, reservationId = 9) shouldBe emptyList()
        dao.yachtCandidateIds(100) shouldContainExactly listOf(26L, 21L)
    }

    @Test
    @Order(2)
    fun `daily sweep - each request once, swap replacement not asked again, second run sends nothing`() {
        val first = invitations.runDailySweep()
        first.bookingSent shouldBe 1
        first.yachtSent shouldBe 2
        first.failed shouldBe 0
        sent.map { it.kind to it.reservationId } shouldContainExactlyInAnyOrder
            listOf(ReviewKind.BOOKING to 1L, ReviewKind.YACHT to 26L, ReviewKind.YACHT to 21L)
        val booking = sent.first { it.kind == ReviewKind.BOOKING }
        booking.locale shouldBe "de"
        booking.url shouldStartWith "https://www.boat4you.com/de/review/"
        ReviewTokens.isWellFormed(token(booking)) shouldBe true

        // Only the hash is stored.
        jdbc.queryForObject("SELECT token_hash FROM review_request WHERE reservation_id = 1 AND kind = 'BOOKING'", String::class.java) shouldBe
            ReviewTokens.hash(token(booking))

        invitations.runDailySweep() shouldBe
            hr.workspace.boat4you.domains.review.service
                .ReviewSweepResult(0, 0, 0)
        invitations.sendBookingInvitationIfEligible(1) shouldBe false
        sent.size shouldBe 3
    }

    @Test
    @Order(3)
    fun `payment event path - sends once for a newly paid booking`() {
        jdbc.update("INSERT INTO reservation_flow VALUES (31, 100, 1, 'ana@example.com', 'Ana', 'Horvat', NULL)")
        jdbc.update(
            "INSERT INTO reservation VALUES (31, 31, LOCALTIMESTAMP + interval '30 days', LOCALTIMESTAMP + interval '37 days', '31/2026', 9031, 'RESERVATION', 'RESERVATION', 10)",
        )
        invitations.sendBookingInvitationIfEligible(31) shouldBe false // not paid yet
        jdbc.update("INSERT INTO reservation_payment_phase (reservation_flow_id, paid_on) VALUES (31, LOCALTIMESTAMP)")
        invitations.sendBookingInvitationIfEligible(31) shouldBe true
        invitations.sendBookingInvitationIfEligible(31) shouldBe false
        sent.count { it.reservationId == 31L } shouldBe 1
    }

    @Test
    @Order(4)
    fun `form - context, create 201, edit within 24 h, 409 after, 404 for bad or expired links`() {
        val bookingToken = token(sent.first { it.kind == ReviewKind.BOOKING && it.reservationId == 1L })

        val form = reviews.getForm(bookingToken)
        form.kind shouldBe ReviewKind.BOOKING
        form.scoreKeys shouldBe ReviewKind.BOOKING.scoreKeys
        form.yachtFullLabel shouldBe "Bavaria Cruiser 46 Tria"
        form.customerFirstName shouldBe "Ana"
        form.baseName shouldBe "ACI Marina Split"
        form.baseCountry shouldBe "Croatia"
        form.locale shouldBe "de"
        form.submitted shouldBe false
        form.editable shouldBe false
        form.review shouldBe null

        val created =
            reviews.submit(
                bookingToken,
                ReviewSubmitRequest(rating = 4, scores = mapOf("communication" to 5), text = "Schnell und klar", publishConsent = true),
            )
        created.created shouldBe true
        created.result.status shouldBe ReviewStatus.NEW
        created.result.review.locale shouldBe "de"

        val edited = reviews.submit(bookingToken, ReviewSubmitRequest(rating = 5, text = "Sehr gut"))
        edited.created shouldBe false
        edited.result.id shouldBe created.result.id
        edited.result.review.rating shouldBe 5
        edited.result.review.scores shouldBe emptyMap()
        edited.result.review.publishConsent shouldBe false

        val row = jdbc.queryForMap("SELECT guest_country, charter_month, user_id, yacht_id FROM reservation_review WHERE id = ?", created.result.id)
        row["guest_country"] shouldBe "Germany"
        row["user_id"] shouldBe 1L
        row["yacht_id"] shouldBe 100L

        reviews.getForm(bookingToken).let {
            it.submitted shouldBe true
            it.editable shouldBe true
            it.review!!.text shouldBe "Sehr gut"
        }

        shouldThrow<ParameterValidationException> { reviews.submit(bookingToken, ReviewSubmitRequest(rating = 5, scores = mapOf("cleanliness" to 5))) }

        jdbc.update("UPDATE reservation_review SET created_at = now() - interval '25 hours' WHERE id = ?", created.result.id)
        shouldThrow<ReviewEditWindowClosedException> { reviews.submit(bookingToken, ReviewSubmitRequest(rating = 1)) }
        reviews.getForm(bookingToken).let {
            it.submitted shouldBe true
            it.editable shouldBe false
            it.review shouldBe null
        }

        shouldThrow<ReviewLinkInvalidException> { reviews.getForm("not-a-token") }
        shouldThrow<ReviewLinkInvalidException> { reviews.getForm(ReviewTokens.generate()) }
        jdbc.update("UPDATE review_request SET expires_at = now() - interval '1 second' WHERE reservation_id = 1 AND kind = 'BOOKING'")
        shouldThrow<ReviewLinkInvalidException> { reviews.getForm(bookingToken) }
        shouldThrow<ReviewLinkInvalidException> { reviews.submit(bookingToken, ReviewSubmitRequest(rating = 5)) }
    }

    @Test
    @Order(5)
    fun `yacht review - accepted without a booking review, linked to one when it exists`() {
        val yachtToken = token(sent.first { it.kind == ReviewKind.YACHT && it.reservationId == 21L })
        reviews.getForm(yachtToken).scoreKeys shouldBe ReviewKind.YACHT.scoreKeys
        val alone = reviews.submit(yachtToken, ReviewSubmitRequest(rating = 5, scores = mapOf("boatCondition" to 5, "value" to 4)))
        alone.created shouldBe true
        jdbc.queryForObject("SELECT booking_review_id FROM reservation_review WHERE id = ?", Long::class.javaObjectType, alone.result.id) shouldBe null

        // Reservation 1 already has its BOOKING review: a YACHT review for it points at that one.
        val raw = ReviewTokens.generate()
        dao.claimRequest(1, ReviewKind.YACHT, ReviewTokens.hash(raw), "en", 60) shouldBe true
        dao.claimRequest(1, ReviewKind.YACHT, ReviewTokens.hash(ReviewTokens.generate()), "en", 60) shouldBe false
        val linked = reviews.submit(raw, ReviewSubmitRequest(rating = 3, scores = mapOf("cleanliness" to 2)))
        jdbc.queryForObject("SELECT booking_review_id FROM reservation_review WHERE id = ?", Long::class.javaObjectType, linked.result.id) shouldBe
            jdbc.queryForObject("SELECT id FROM reservation_review WHERE reservation_id = 1 AND kind = 'BOOKING'", Long::class.javaObjectType)
    }

    @Test
    @Order(6)
    fun `admin - filter, page, moderate`() {
        reviews.listForAdmin(null, null, 0, 20).totalElements shouldBe 3
        val yacht = reviews.listForAdmin(ReviewKind.YACHT, ReviewStatus.NEW, 0, 1)
        yacht.totalElements shouldBe 2
        yacht.content.size shouldBe 1
        yacht.totalPages shouldBe 2
        val first = yacht.content.single()
        first.yachtFullLabel shouldBe "Bavaria Cruiser 46 Tria"
        first.customerName shouldBe "Ana Horvat"
        first.charterMonth!!.length shouldBe 7

        val published = reviews.setStatus(first.id, "published", 3)
        published.status shouldBe ReviewStatus.PUBLISHED
        (published.statusChangedAt != null) shouldBe true
        reviews.listForAdmin(null, ReviewStatus.PUBLISHED, 0, 20).content.map { it.id } shouldBe listOf(first.id)

        shouldThrow<ParameterValidationException> { reviews.setStatus(first.id, "LIVE", 3) }
        shouldThrow<ReviewNotFoundException> { reviews.setStatus(999_999, "HIDDEN", 3) }
    }

    @Test
    @Order(7)
    fun `GDPR erasure - text, country and consent go, review hidden, rating kept`() {
        (dao.anonymiseForUser(1) >= 3) shouldBe true
        jdbc.queryForList("SELECT DISTINCT status FROM reservation_review WHERE user_id = 1", String::class.java) shouldBe listOf("HIDDEN")
        jdbc.queryForObject(
            "SELECT count(*) FROM reservation_review WHERE user_id = 1 AND (text IS NOT NULL OR guest_country IS NOT NULL OR publish_consent)",
            Long::class.java,
        ) shouldBe 0L
        jdbc.queryForObject("SELECT count(*) FROM reservation_review WHERE rating IS NULL", Long::class.java) shouldBe 0L
    }

    @Test
    @Order(8)
    fun `reservation purge cascades to its review rows`() {
        jdbc.update("DELETE FROM reservation WHERE id = 21")
        jdbc.queryForObject("SELECT count(*) FROM reservation_review WHERE reservation_id = 21", Long::class.java) shouldBe 0L
        jdbc.queryForObject("SELECT count(*) FROM review_request WHERE reservation_id = 21", Long::class.java) shouldBe 0L
    }
}
