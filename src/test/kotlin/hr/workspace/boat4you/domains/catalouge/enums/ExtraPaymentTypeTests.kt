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
            ExtraPaymentType.fromNausysCalculationType("ADVANCE_PAYMENT", BigDecimal("1540"), obligatory = false, optionalOnSite = false),
        )
    }

    @Test
    fun `MMK yacht-level classification is unchanged`() {
        for (on in listOf(false, true)) {
            assertEquals(ExtraPaymentType.ON_SITE, mmkYacht("Kayak", "150", payableInBase = false, obligatory = false, on))
            assertEquals(ExtraPaymentType.ADVANCE_TO_OPERATOR, mmkYacht("Skipper", "1820", payableInBase = false, obligatory = false, on))
            assertEquals(ExtraPaymentType.ON_SITE, mmkYacht("Tourist tax", "1.33", payableInBase = true, obligatory = true, on))
        }
        assertEquals(ExtraPaymentType.INCLUDED, mmkYacht("Outboard engine", "0", payableInBase = true, obligatory = false, on = false))
    }

    private fun mmkYacht(
        name: String,
        price: String?,
        payableInBase: Boolean,
        obligatory: Boolean,
        on: Boolean,
    ) = ExtraPaymentType.fromMmkPayableInBase(name, price?.let(::BigDecimal), payableInBase, obligatory, optionalOnSite = on)

    private fun nausys(
        calculationType: String?,
        price: String?,
        obligatory: Boolean,
        on: Boolean,
        isPercentage: Boolean = false,
    ) = ExtraPaymentType.fromNausysCalculationType(calculationType, price?.let(::BigDecimal), obligatory, optionalOnSite = on, isPercentage)

    // --- D6 (Mario 14.10.2026): unpriced extras, switch EXTRAS_ZERO_OPTIONAL_ON_SITE ---

    @Test
    fun `zeroPrice - obligatory is included, optional is on site only with the switch on`() {
        assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.zeroPrice(obligatory = true, optionalOnSite = true))
        assertEquals(ExtraPaymentType.ON_SITE, ExtraPaymentType.zeroPrice(obligatory = false, optionalOnSite = true))
        assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.zeroPrice(obligatory = true, optionalOnSite = false))
        assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.zeroPrice(obligatory = false, optionalOnSite = false))
    }

    @Test
    fun `switch ON - a free obligatory extra is included and a free optional one is on site, in every classifier`() {
        for (price in listOf("0", "0.00", null)) {
            // classify (generic / calc fallback)
            assertEquals(
                ExtraPaymentType.INCLUDED,
                ExtraPaymentType.classify("Skipper", price?.let(::BigDecimal), payableInBase = true, obligatory = true, optionalOnSite = true),
            )
            assertEquals(
                ExtraPaymentType.ON_SITE,
                ExtraPaymentType.classify("Skipper", price?.let(::BigDecimal), payableInBase = true, obligatory = false, optionalOnSite = true),
            )
            // MMK yacht level
            assertEquals(ExtraPaymentType.INCLUDED, mmkYacht("Damage waiver", price, payableInBase = false, obligatory = true, on = true))
            assertEquals(ExtraPaymentType.ON_SITE, mmkYacht("Damage waiver", price, payableInBase = false, obligatory = false, on = true))
            // NauSys yacht / offer level
            for (type in listOf("SEPARATE_PAYMENT", "ADVANCE_PAYMENT", "INCLUDED_IN_PRICE", null)) {
                assertEquals(ExtraPaymentType.INCLUDED, nausys(type, price, obligatory = true, on = true))
                assertEquals(ExtraPaymentType.ON_SITE, nausys(type, price, obligatory = false, on = true))
                // The offer's own obligatory lists are obligatory by definition: always INCLUDED when free.
                assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.fromNausysOfferObligatory(type, price?.let(::BigDecimal)))
            }
            assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.fromMmkOfferObligatory(price?.let(::BigDecimal), payableInBase = false))
            assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.fromMmkOfferObligatory(price?.let(::BigDecimal), payableInBase = true))
        }
    }

    @Test
    fun `switch OFF - every free extra stays INCLUDED, as today`() {
        for (price in listOf("0", null)) {
            for (obligatory in listOf(true, false)) {
                assertEquals(
                    ExtraPaymentType.INCLUDED,
                    ExtraPaymentType.classify("Skipper", price?.let(::BigDecimal), payableInBase = false, obligatory = obligatory, optionalOnSite = false),
                )
                assertEquals(ExtraPaymentType.INCLUDED, mmkYacht("SUP", price, payableInBase = false, obligatory = obligatory, on = false))
                for (type in listOf("SEPARATE_PAYMENT", "ADVANCE_PAYMENT", "INCLUDED_IN_PRICE", null)) {
                    assertEquals(ExtraPaymentType.INCLUDED, nausys(type, price, obligatory = obligatory, on = false))
                    // A NauSys percentage item (stored at 0) also stays INCLUDED while the switch is OFF.
                    assertEquals(ExtraPaymentType.INCLUDED, nausys(type, price, obligatory = obligatory, on = false, isPercentage = true))
                }
            }
        }
        // The defaults of classify() are the historical behaviour (read path of bookings without a stored type).
        assertEquals(ExtraPaymentType.INCLUDED, ExtraPaymentType.classify("Skipper", BigDecimal.ZERO, payableInBase = true))
    }

    @Test
    fun `switch ON - a NauSys percentage item is classified by calculationType and never reads INCLUDED`() {
        for (obligatory in listOf(true, false)) {
            assertEquals(ExtraPaymentType.ON_SITE, nausys("SEPARATE_PAYMENT", "0", obligatory, on = true, isPercentage = true))
            assertEquals(ExtraPaymentType.ADVANCE_TO_OPERATOR, nausys("ADVANCE_PAYMENT", "0", obligatory, on = true, isPercentage = true))
            assertEquals(ExtraPaymentType.WITH_BOOKING, nausys("INCLUDED_IN_PRICE", "0", obligatory, on = true, isPercentage = true))
            assertEquals(ExtraPaymentType.ADVANCE_TO_OPERATOR, nausys(null, "0", obligatory, on = true, isPercentage = true))
        }
        // An obligatory % charge reads "On request, paid in the marina" only when the partner says SEPARATE_PAYMENT.
        assertEquals(ExtraPaymentType.WITH_BOOKING, nausys("INCLUDED_IN_PRICE", "0", obligatory = true, on = true, isPercentage = true))
    }

    @Test
    fun `a priced extra ignores the switch and the obligatory flag`() {
        for (on in listOf(false, true)) {
            for (obligatory in listOf(true, false)) {
                assertEquals(ExtraPaymentType.ON_SITE, nausys("SEPARATE_PAYMENT", "370", obligatory, on))
                assertEquals(ExtraPaymentType.WITH_BOOKING, nausys("INCLUDED_IN_PRICE", "90", obligatory, on))
                assertEquals(
                    ExtraPaymentType.WITH_BOOKING,
                    ExtraPaymentType.classify("Kayak", BigDecimal("150"), payableInBase = false, obligatory = obligatory, optionalOnSite = on),
                )
            }
        }
    }
}
