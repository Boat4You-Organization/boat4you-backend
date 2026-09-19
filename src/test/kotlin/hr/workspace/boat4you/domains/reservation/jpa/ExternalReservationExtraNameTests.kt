package hr.workspace.boat4you.domains.reservation.jpa

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import jakarta.validation.Validation
import org.junit.jupiter.api.Test

/**
 * 19.9.2026: partner-owned extra text is longer than our columns were. MMK's obligatory "Charter pack (Includes: …)"
 * is 222 chars against the former `external_reservation_extras.name varchar(200)`; bean validation rejected it at flush
 * and every booking of that yacht rolled back AFTER the partner option had been created. The sibling
 * `reservation_extras.yacht_extras_key varchar(255)` takes the same partner name as a key when the extra has no
 * catalogue mapping (264 chars on MMK yacht 7828). V9_58 made both unbounded; this pins the entity side of that.
 */
class ExternalReservationExtraNameTests {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    private val partnerName = "Exclusive Pack: 3 crew, bed linen, bath and beach towels, bathrobes, dinghy with outboard. ".repeat(3)

    @Test
    fun `a partner extra name of any length validates`() {
        val extra = ExternalReservationExtra().apply { name = partnerName }

        (partnerName.length > 255) shouldBe true
        validator.validateProperty(extra, "name").shouldBeEmpty()
    }

    @Test
    fun `a partner name used as the extras key validates and is kept whole`() {
        val extra = ReservationExtra().apply { yachtExtrasKey = partnerName }

        validator.validateProperty(extra, "yachtExtrasKey").shouldBeEmpty()
        // Never cut: the key is matched back against extrasKey() when the booking is priced and displayed.
        extra.yachtExtrasKey shouldBe partnerName
    }
}
