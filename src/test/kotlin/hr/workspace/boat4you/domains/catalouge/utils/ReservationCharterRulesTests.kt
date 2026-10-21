package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.enums.CharterType.ALL_INCLUSIVE
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.BAREBOAT
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CREWED
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CRUISE
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.UNKNOWN
import hr.workspace.boat4you.domains.catalouge.utils.ReservationCharterRules.Kind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Mario 21.10.2026: "GULET JE UVIJEK CREWED" - the charter type a reservation shows. The agreement changes only for a
 * gulet ("ugovor ne trebaš sada dirat"): every other boat as before, a skipper extra or bareboat.
 */
class ReservationCharterRulesTests {
    @Test
    fun `a gulet is crewed - without a skipper, with one`() {
        ReservationCharterRules.kind(gulet = true, extraNames = emptyList()) shouldBe Kind.CREWED
        ReservationCharterRules.kind(gulet = true, extraNames = listOf("Skipper")) shouldBe Kind.CREWED
        ReservationCharterRules.kind(gulet = true, extraNames = listOf("Transit log", null)) shouldBe Kind.CREWED
    }

    @Test
    fun `every other boat - a skipper extra is skippered, without one bareboat`() {
        ReservationCharterRules.kind(gulet = false, extraNames = listOf("Transit log", "SKIPPER (per day)")) shouldBe Kind.SKIPPERED
        ReservationCharterRules.kind(gulet = false, extraNames = listOf(null, "skipper")) shouldBe Kind.SKIPPERED
        ReservationCharterRules.kind(gulet = false, extraNames = listOf("Transit log", null)) shouldBe Kind.BAREBOAT
        ReservationCharterRules.kind(gulet = false, extraNames = emptyList()) shouldBe Kind.BAREBOAT
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
