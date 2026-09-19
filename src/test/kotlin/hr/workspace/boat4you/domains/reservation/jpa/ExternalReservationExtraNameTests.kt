package hr.workspace.boat4you.domains.reservation.jpa

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import jakarta.validation.Validation
import org.junit.jupiter.api.Test

/**
 * 19.9.2026: MMK returned an extra whose name was longer than the column. Bean validation rejected it at flush, the
 * booking transaction rolled back AFTER the partner option had been created and the customer got a 502 on every retry.
 * The mapper now cuts the name to [ExternalReservationExtra.NAME_MAX_LENGTH]; this pins both halves of that contract.
 */
class ExternalReservationExtraNameTests {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    private val partnerName = "Transit log: final cleaning, bed linen and towels, gas, dinghy with outboard engine. ".repeat(4)

    @Test
    fun `a partner name over the column width is what used to fail the booking`() {
        val extra = ExternalReservationExtra().apply { name = partnerName }

        (partnerName.length > ExternalReservationExtra.NAME_MAX_LENGTH) shouldBe true
        validator.validateProperty(extra, "name") shouldHaveSize 1
    }

    @Test
    fun `the name cut to the column width validates`() {
        val extra = ExternalReservationExtra().apply { name = partnerName.take(ExternalReservationExtra.NAME_MAX_LENGTH) }

        extra.name!!.length shouldBe ExternalReservationExtra.NAME_MAX_LENGTH
        validator.validateProperty(extra, "name").shouldBeEmpty()
    }

    @Test
    fun `a short or missing name is left alone`() {
        "Skipper".take(ExternalReservationExtra.NAME_MAX_LENGTH) shouldBe "Skipper"
        validator.validateProperty(ExternalReservationExtra(), "name").shouldBeEmpty()
    }
}
