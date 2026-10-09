package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.ALL_INCLUSIVE
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.BAREBOAT
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CREWED
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CRUISE
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Mario 9.10.2026: a gulet is never bareboat - detected by vessel type or by the model name (prod: Gallant 11771). */
class GuletRulesTests {
    @Test
    fun `a gulet by vessel type or by model name, any case`() {
        GuletRules.isGulet(VesselType.GULET, null) shouldBe true
        GuletRules.isGulet(VesselType.GULET, "Custom") shouldBe true
        // b4y Gallant 11771: MOTOR_YACHT, model "Gulet"
        GuletRules.isGulet(VesselType.MOTOR_YACHT, "Gulet") shouldBe true
        GuletRules.isGulet(VesselType.SAILING_YACHT, "Turkish GULET 24m") shouldBe true
        GuletRules.isGulet(null, "gulet") shouldBe true
    }

    @Test
    fun `not a gulet`() {
        GuletRules.isGulet(VesselType.MOTOR_YACHT, "Princess 62") shouldBe false
        GuletRules.isGulet(VesselType.SAILING_YACHT, null) shouldBe false
        GuletRules.isGulet(null, null) shouldBe false
        GuletRules.isGulet(VesselType.CATAMARAN, "Lagoon 42") shouldBe false
    }

    @Test
    fun `boat page charter types - a gulet without BAREBOAT, CREWED when nothing is left`() {
        // Sylvia R 18886: the partner tags it BAREBOAT only
        GuletRules.publicCharterTypes(setOf(BAREBOAT), gulet = true) shouldBe setOf(CREWED)
        GuletRules.publicCharterTypes(setOf(BAREBOAT, CREWED), gulet = true) shouldBe setOf(CREWED)
        GuletRules.publicCharterTypes(setOf(BAREBOAT, ALL_INCLUSIVE), gulet = true) shouldBe setOf(ALL_INCLUSIVE)
        GuletRules.publicCharterTypes(setOf(CREWED, ALL_INCLUSIVE), gulet = true) shouldBe setOf(CREWED, ALL_INCLUSIVE)
        GuletRules.publicCharterTypes(emptySet(), gulet = true) shouldBe setOf(CREWED)
    }

    @Test
    fun `every other boat keeps its partner types`() {
        GuletRules.publicCharterTypes(setOf(BAREBOAT), gulet = false) shouldBe setOf(BAREBOAT)
        GuletRules.publicCharterTypes(setOf(BAREBOAT, CREWED), gulet = false) shouldBe setOf(BAREBOAT, CREWED)
        GuletRules.publicCharterTypes(emptySet(), gulet = false) shouldBe emptySet()
        GuletRules.publicCharterType(BAREBOAT, gulet = false) shouldBe BAREBOAT
        GuletRules.publicCharterType(null, gulet = false) shouldBe null
    }

    @Test
    fun `listing charter type of a gulet`() {
        GuletRules.publicCharterType(null, gulet = true) shouldBe CREWED
        GuletRules.publicCharterType(BAREBOAT, gulet = true) shouldBe CREWED
        GuletRules.publicCharterType(ALL_INCLUSIVE, gulet = true) shouldBe ALL_INCLUSIVE
        GuletRules.publicCharterType(CRUISE, gulet = true) shouldBe CRUISE
    }

    @Test
    fun `native filter - BAREBOAT never matches a gulet, CREWED matches every gulet`() {
        val gulet = GuletRules.SQL_IS_GULET
        val listed = "(charter_type IN (:names) AND NOT (charter_type = 'BAREBOAT' AND $gulet))"
        GuletRules.charterTypeSql(listOf(BAREBOAT), "names") shouldBe listed
        GuletRules.charterTypeSql(listOf<CharterType>(ALL_INCLUSIVE), "names") shouldBe listed
        GuletRules.charterTypeSql(listOf(BAREBOAT, CREWED), "names") shouldBe "($listed OR $gulet)"
        gulet shouldBe "(COALESCE(vessel_type, '') = 'GULET' OR COALESCE(model_name, '') ILIKE '%gulet%')"
    }
}
