package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.enums.CharterType.ALL_INCLUSIVE
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.BAREBOAT
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CREWED
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CRUISE
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.UNKNOWN
import hr.workspace.boat4you.domains.catalouge.utils.ReservationCharterRules.Kind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Mario 21.10.2026: "GULET JE UVIJEK CREWED" - the charter type a reservation shows. */
class ReservationCharterRulesTests {
    @Test
    fun `a gulet is crewed - without a skipper, with one, whatever the offer says`() {
        ReservationCharterRules.kind(gulet = true, product = BAREBOAT, extraNames = emptyList()) shouldBe Kind.CREWED
        ReservationCharterRules.kind(gulet = true, product = BAREBOAT, extraNames = listOf("Skipper")) shouldBe Kind.CREWED
        ReservationCharterRules.kind(gulet = true, product = null, extraNames = listOf("Transit log")) shouldBe Kind.CREWED
        ReservationCharterRules.kind(gulet = true, product = UNKNOWN, extraNames = emptyList()) shouldBe Kind.CREWED
    }

    @Test
    fun `a crewed offer product is crewed on any boat`() {
        ReservationCharterRules.kind(gulet = false, product = CREWED, extraNames = emptyList()) shouldBe Kind.CREWED
        ReservationCharterRules.kind(gulet = false, product = CREWED, extraNames = listOf("Skipper")) shouldBe Kind.CREWED
        ReservationCharterRules.kind(gulet = false, product = ALL_INCLUSIVE, extraNames = emptyList()) shouldBe Kind.CREWED
        ReservationCharterRules.kind(gulet = false, product = CRUISE, extraNames = emptyList()) shouldBe Kind.CREWED
    }

    @Test
    fun `a sailing yacht with a skipper extra is skippered, without one bareboat`() {
        ReservationCharterRules.kind(gulet = false, product = BAREBOAT, extraNames = listOf("Transit log", "SKIPPER (per day)")) shouldBe Kind.SKIPPERED
        ReservationCharterRules.kind(gulet = false, product = null, extraNames = listOf(null, "skipper")) shouldBe Kind.SKIPPERED
        ReservationCharterRules.kind(gulet = false, product = BAREBOAT, extraNames = listOf("Transit log", null)) shouldBe Kind.BAREBOAT
        ReservationCharterRules.kind(gulet = false, product = null, extraNames = emptyList()) shouldBe Kind.BAREBOAT
        ReservationCharterRules.kind(gulet = false, product = UNKNOWN, extraNames = emptyList()) shouldBe Kind.BAREBOAT
    }

    @Test
    fun `the agreement labels`() {
        Kind.BAREBOAT.label shouldBe "Bareboat charter"
        Kind.SKIPPERED.label shouldBe "Skippered charter"
        Kind.CREWED.label shouldBe "Crewed charter"
    }

    @Test
    fun `the DTO type - a gulet's is CREWED, every other boat keeps the offer's product`() {
        ReservationCharterRules.displayType(BAREBOAT, gulet = true) shouldBe CREWED
        ReservationCharterRules.displayType(null, gulet = true) shouldBe CREWED
        ReservationCharterRules.displayType(UNKNOWN, gulet = true) shouldBe CREWED
        ReservationCharterRules.displayType(CREWED, gulet = true) shouldBe CREWED
        ReservationCharterRules.displayType(ALL_INCLUSIVE, gulet = true) shouldBe ALL_INCLUSIVE
        ReservationCharterRules.displayType(CRUISE, gulet = true) shouldBe CRUISE

        ReservationCharterRules.displayType(BAREBOAT, gulet = false) shouldBe BAREBOAT
        ReservationCharterRules.displayType(CREWED, gulet = false) shouldBe CREWED
        ReservationCharterRules.displayType(UNKNOWN, gulet = false) shouldBe UNKNOWN
        ReservationCharterRules.displayType(null, gulet = false) shouldBe null
    }
}
