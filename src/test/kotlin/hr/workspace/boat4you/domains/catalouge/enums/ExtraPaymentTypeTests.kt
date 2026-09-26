package hr.workspace.boat4you.domains.catalouge.enums

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class ExtraPaymentTypeTests {
    @Test
    fun `MMK offer obligatory extra billed in the reservation price is paid with the booking`() {
        // 1441012/2026: "Additional fixed part" 350 EUR, payableInBase=false, inside MMK's clientPrice.
        assertEquals(
            ExtraPaymentType.WITH_BOOKING,
            ExtraPaymentType.fromMmkOfferObligatory(BigDecimal("350.0"), payableInBase = false),
        )
        // Crew and APA names do not matter on the offer path — MMK bills them with the booking too.
        assertEquals(
            ExtraPaymentType.WITH_BOOKING,
            ExtraPaymentType.fromMmkOfferObligatory(BigDecimal("8000"), payableInBase = false),
        )
    }

    @Test
    fun `MMK offer obligatory extra payable in base stays at the marina and a free one is included`() {
        assertEquals(
            ExtraPaymentType.ON_SITE,
            ExtraPaymentType.fromMmkOfferObligatory(BigDecimal("2.66"), payableInBase = true),
        )
        assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.fromMmkOfferObligatory(BigDecimal.ZERO, payableInBase = false))
        assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.fromMmkOfferObligatory(null, payableInBase = false))
    }

    @Test
    fun `NauSys offer obligatory ADVANCE_PAYMENT extra is paid with the booking`() {
        assertEquals(
            ExtraPaymentType.WITH_BOOKING,
            ExtraPaymentType.fromNausysOfferObligatory("ADVANCE_PAYMENT", BigDecimal("440")),
        )
        // The other NauSys types keep their meaning.
        assertEquals(ExtraPaymentType.ON_SITE, ExtraPaymentType.fromNausysOfferObligatory("SEPARATE_PAYMENT", BigDecimal("370")))
        assertEquals(ExtraPaymentType.WITH_BOOKING, ExtraPaymentType.fromNausysOfferObligatory("INCLUDED_IN_PRICE", BigDecimal("90")))
        assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.fromNausysOfferObligatory("ADVANCE_PAYMENT", BigDecimal.ZERO))
        // Unknown type: no partner signal that it is prepaid, so it stays where it was.
        assertEquals(ExtraPaymentType.ADVANCE_TO_OPERATOR, ExtraPaymentType.fromNausysOfferObligatory(null, BigDecimal("100")))
        // Optional NauSys extras keep the plain mapping.
        assertEquals(
            ExtraPaymentType.ADVANCE_TO_OPERATOR,
            ExtraPaymentType.fromNausysCalculationType("ADVANCE_PAYMENT", BigDecimal("1540")),
        )
    }

    @Test
    fun `MMK yacht-level classification is unchanged`() {
        assertEquals(ExtraPaymentType.ON_SITE, ExtraPaymentType.fromMmkPayableInBase("Kayak", BigDecimal("150"), payableInBase = false))
        assertEquals(
            ExtraPaymentType.ADVANCE_TO_OPERATOR,
            ExtraPaymentType.fromMmkPayableInBase("Skipper", BigDecimal("1820"), payableInBase = false),
        )
        assertEquals(ExtraPaymentType.ON_SITE, ExtraPaymentType.fromMmkPayableInBase("Tourist tax", BigDecimal("1.33"), payableInBase = true))
        assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.fromMmkPayableInBase("Outboard engine", BigDecimal.ZERO, payableInBase = true))
    }
}
