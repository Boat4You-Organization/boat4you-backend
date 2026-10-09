package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.services.toDto
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Mario 9.10.2026: the boat's layout on its own above the amenities. File names below are prod external_url names (9.10.2026). */
class LayoutImageRulesTests {
    private val mmk = "https://www.booking-manager.com/cbm/documents/"
    private val nausys = "https://ws.nausys.com/CBMS-external/rest/yacht/103152/pictures/"

    private fun layout(vararg names: String) = names.filter { !LayoutImageRules.isLayout(mmk + it) }

    private fun photo(vararg names: String) = names.filter { LayoutImageRules.isLayout(mmk + it) }

    @Test
    fun `Lagoon 55 The Moon 12663 - the layout yes, the side view (main image) and the photos no`() {
        LayoutImageRules.isLayout(mmk + "36397280967800260_1695203065343_L55_layout_5.jpg") shouldBe true // yacht_image 218650
        LayoutImageRules.isLayout(mmk + "36397340967800260_L55_vistalato.jpg") shouldBe false
        LayoutImageRules.isLayout(mmk + "37114131586300260_themoon_2071.jpg") shouldBe false
    }

    @Test
    fun `NauSys names its layout picture layout - the rest are main, n1, p2, slika (3)`() {
        LayoutImageRules.isLayout(nausys + "layout.jpg") shouldBe true
        LayoutImageRules.isLayout(nausys + "layout.png") shouldBe true
        LayoutImageRules.isLayout(nausys + "layout2.jpg") shouldBe true
        LayoutImageRules.isLayout(nausys + "layout.jpg?w=600") shouldBe true
        LayoutImageRules.isLayout(nausys + "main.jpg") shouldBe false
        LayoutImageRules.isLayout(nausys + "n1.jpg") shouldBe false
        LayoutImageRules.isLayout(nausys + "p2.jpeg") shouldBe false
        LayoutImageRules.isLayout(nausys + "slika%20(3).jpg") shouldBe false
    }

    @Test
    fun `layout and lay, any case, percent-encoded, typos`() {
        layout(
            "2613369260000100000_bav_cr_46%284cab%29_layout.jpg",
            "4506121070000102889_sunsail_424_%28leopard_42%29_-_layout.jpg",
            "1193453750000100000_sun_odyssey_49i_layout_8%2b2.jpg",
            "L4SC-LAYOUT-LOWERDECK-4C4T.JPG",
            "searunneriilayout.jpg",
            "catamaran-lagoon-46-layout-cockpit.jpg",
            "lay.jpg",
            "s41-lay.jpg",
            "oceanis51.1_lay1.jpg",
            "sunodyssey449lay.jpg",
            "lay_out_lagoon_46.jpg",
            "dufour-37-sailing-yacht-luxury-deck-lay-out-3-cabins.jpg",
            "b46cn%20lay%20out%202018.jpg",
            "layaout_bali_4.6.jpg",
            "dufor_455_layaut.jpg",
            "bali_layot.jpg",
            "coral_layuot.jpg",
            "ammos4_layojut.jpg",
            "layput1.jpg",
            "so_39i-layou.jpg",
            "100%_layout.jpg", // not valid percent-encoding: read as it is
        ) shouldBe emptyList()
    }

    @Test
    fun `plan - alone, at the end of a word, plural`() {
        layout(
            "1648714680460_plan.jpg",
            "lagoon_380_plan.jpg",
            "balu_deck_plan.jpg",
            "23_cat_olea_cabin_floor_plan.jpg",
            "grego_bavaria-cruiser-45_floorplan.jpg",
            "aqua_sunplan.jpg",
            "24082017111449_groundplan_interior.jpg",
            "lagoon450fplanellupinplan2.jpg",
            "plans_5.jpg",
            "boat-sun-odyssey_plans_2013101811483545.jpg",
            "plan-cabine-lagoon-42-4-cabines.jpg",
            "solara_plan_intrieur.jpg",
            "1576536130358_oceanis-46.1_planinterieur.jpg",
            "cool_plan.jpg",
        ) shouldBe emptyList()
    }

    @Test
    fun `other languages`() {
        layout(
            "pianta_lagoon_46.jpg",
            "piantastella.jpg",
            "piantina_bavaria_cr._46.jpg",
            "kaptan-yilmaz-planimetria2.jpg",
            "disposizione-509.jpg",
            "plano_barco.jpg",
            "dufour_385_plano.jpg",
            "planta_cyclades_50.4.jpg",
            "planol.jpg",
            "plaan.jpg",
            "rzut.jpg",
            "cr50_5-4_grundriss_01.jpg",
            "1681298773502_03_grundrissmittel.jpg",
            "15112016110945_tlocrt_luka_4_3_bijelo_na_sivo.jpg",
            "bali%204.6%20raspored.jpg",
            "f44_tloris_2_cab.jpg",
            "indeling_dehler_34.jpg",
            "1737020152153_plan_plattegrond_bavaria_46_2017.jpg",
            "lagoon_42_3_cabinas_distribucion.jpg",
            "distribucin_interior.jpg",
            "distribuci%C3%B3n.jpg",
            "beneteau_46.1_interior_desing_blueprint.jpg",
        ) shouldBe emptyList()
    }

    @Test
    fun `never a photo - planet, plants, play, relay, deck, side views, sail plans`() {
        photo(
            "dufour-460-discovery-planet-ext-01.jpg",
            "happyplanet_int1.jpg",
            "wonderplanet_main.jpg",
            "piante_in_vaso.jpg", // it: potted plants
            "piantine_aromatiche.jpg", // it: herb seedlings
            "plantas.jpg", // es: plants
            "plant_on_deck.jpg",
            "explanation.jpg",
            "n4%20-%20plancha%202.jpg",
            "plancha_grill.jpeg",
            "dufour_382_plancia_poppa.jpg",
            "plancetta.jpg",
            "planis_ext1.jpg",
            "hanse-508-e2d2c1b3a2_%28planaria%29.jpg",
            "52_plane.jpg",
            "layla-twin-bunk.jpg",
            "dufour_360_grand_large_-_layla04.jpg",
            "playmaker_int29.jpg",
            "4play0024.jpg",
            "playablanca_r.jpg",
            "background-relay.jpg",
            "breakfast_fruit_display.jpg",
            "dufour_48_layback.jpg",
            "layer_sun_odyssey_490.jpg",
            "yellow_bird_layover.jpg",
            "2571370919503319_4_-_fwd_deck_%284%29.jpg",
            "29990501304400174_lagoon_46_-_queen_nika_-_2020_-_deck_%282%29.jpg",
            "side_view.jpg",
            "main.%20a1%20dea%20profilna.jpg",
            "drawing_stock.jpg",
            "tw_scheme.jpg",
            "rm-1070-en-location-bretagne-nord-amenagement-intrieur.jpg",
            "dufour-sail-plan-luxury-sailing-yacht-dufour-430-745x1024.jpg",
            "sail_plan.jpg",
        ) shouldBe emptyList()
    }

    @Test
    fun `either url counts - our stored url is a UUID webp and never matches`() {
        LayoutImageRules.isLayout(null, "y-12663/3f1c2a9e-8b7d-4e6f-a1b2-c3d4e5f60718.webp") shouldBe false
        LayoutImageRules.isLayout(null, "y-1/deck_plan.webp") shouldBe true
        LayoutImageRules.isLayout(mmk + "l42_plan.jpg", null) shouldBe true
        LayoutImageRules.isLayout(null, null) shouldBe false
        LayoutImageRules.isLayout("", " ") shouldBe false
    }

    @Test
    fun `the image DTO carries the flag on every item`() {
        val plan =
            YachtImage().apply {
                id = 218650
                externalUrl = mmk + "36397280967800260_1695203065343_L55_layout_5.jpg"
                url = "y-12663/3f1c2a9e-8b7d-4e6f-a1b2-c3d4e5f60718.webp"
            }
        val side =
            YachtImage().apply {
                id = 218647
                externalUrl = mmk + "36397340967800260_L55_vistalato.jpg"
                mainImage = true
            }
        val upload = YachtImage().apply { id = 1 }
        plan.toDto().layout shouldBe true
        side.toDto().layout shouldBe false
        side.toDto().mainImage shouldBe true
        upload.toDto().layout shouldBe false
    }
}
