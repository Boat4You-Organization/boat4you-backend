package hr.workspace.boat4you.domains.catalouge.utils

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * An image is the boat's layout (deck / floor plan) - Mario 9.10.2026: the boat page shows it on its own above the
 * amenities instead of somewhere in the gallery. Public detail DTO: `yachtImages[].layout`.
 *
 * Read from the file name. MMK (Booking Manager) keeps the name the charter company uploaded
 * (Lagoon 55 The Moon 12663: ".../36397280967800260_1695203065343_L55_layout_5.jpg"); NauSys names the picture it flags
 * `RestYachtPicture.layoutPicture` "layout.jpg" / ".png" itself (API v6 docs). Neither partner flag is stored: MMK's
 * `Image.description` is free text ("Main image"), and NauSys' flag is already in the name (5,593 of 5,833 NauSys boats).
 * Our stored url is a random UUID .webp - checked anyway, an admin upload could keep its name one day.
 *
 * Each word decided on the prod file names of 9.10.2026 (13,030 active boats with images, 232,927 images; flagged 11,245
 * boats / 11,922 images). Matched on the lowercase, percent-decoded name without accents, a sail plan (a rig drawing,
 * 2 images) taken out first.
 *
 * Never flagged: planet (95, "smile-planet-ext-01" photo sets), plancha (griddle), plancia (helm), plancetta (swim
 * platform), planis / planaria (boat names), plane, play / playa / layla / relay / display / layback / layer / layover,
 * deck (deck photos), side views (vistalato 95 - The Moon's main image -, side_view, profile), and the ambiguous drawing
 * (3 of 5 are side elevations), piano (a boat named Piano, piano_cottura = hob), amenagement (fitting-out photos too) and
 * "plana" (Spanish: flat).
 *
 * Review 9.10.2026 (each image looked at): 281 flagged - 279 plans, 2 photos the charter company itself named "layout"
 * (victoria_layout_3 aerial shot, a helm render) -; 100 random unflagged - 99 photos, 1 plan with an opaque name
 * (catspace-4cab). The typo / riss / nacrt / scheme / diagram words were added after that review: 54 more plans.
 */
object LayoutImageRules {
    val WORDS: List<Regex> =
        listOf(
            """layout""", // 11,185 images: every partner; NauSys layout.jpg / layout.png / layout2.jpg
            """(?<![a-z])lay(?![a-z])""", // lay.jpg (12), s41-lay.jpg, oceanis51.1_lay1, lay_out, lay-out, "b46cn lay out 2018"
            """lay[aoujp]{1,3}t|layou(?![a-z])""", // typos: layaout, layaut, layot, layuot, layojut, layput, layou
            // Typos as a whole word (17 images, each one a plan): leaout (7), lauout, lazout (qwertz), laout, laoyut, lagouyt,
            // lyaout, loyout, loyaout, lyout, iayout. Never lout ("410lout" photo), chillout, lookout, input, about.
            """(?<![a-z])[li][aeouyzg]{2,4}uy?t(?![a-z])""",
            """plans?(?![a-z])|deck[-_ ]?plane""", // plan.jpg (62), l42_plan, deck_plan, floorplan, sunplan, plans_5; ap_deck_plane
            """planinteri""", // oceanis-46.1_planinterieur (fr)
            """(?<![a-z])(planos?|planta|planol|plaan|rzut)(?![a-z])""", // es plano (20) / planta (3), ca planol, et plaan, pl rzut
            """pianta|piantina|planimetri|disposizione""", // it: all 44 "pianta" are plans (pianta_lagoon_46, piantastella)
            """(?<![a-z])piant(?![a-z])|piano[-_ ]?interni|configurazione""", // it: hunter_32.6_piant, piano_interni, configurazione_lagoon_421
            """grunds?riss|(?<![a-z])riss(?![a-z])""", // de: grundriss, grundsriss; "Riss" (plan drawing): riss.jpg, yacht-400-riss (19, all plans)
            """tlocrt|raspored|nacrt""", // hr: nacrt_salon / nacrt_spavace / nacrt_vanjski_dio (deck plans)
            """tloris""", // sl
            """plattegrond|indeling""", // nl
            """distribucio?n""", // es distribucion (15), distribucin (the partner dropped the accent)
            """blueprint|diagram""", // mmk.diagram
            """(?<![a-z])sc?hem[ae](?!r)""", // hr shema / sheme, en scheme, it schema (10 images, all plans: oceanis-48_sheme, tw_scheme)
        ).map { Regex(it) }

    private val sailPlan = Regex("""sail[-_ ]?plans?""")
    private val accents = Regex("""\p{Mn}+""")

    /** Any of the urls (partner external_url, our stored url) is a layout image. */
    fun isLayout(vararg urls: String?): Boolean =
        urls.any { url ->
            if (url.isNullOrBlank()) return@any false
            val name = sailPlan.replace(fileName(url), " ")
            WORDS.any { it.containsMatchIn(name) }
        }

    /** The url's last path segment without query / fragment: percent-decoded, lowercase, accents stripped. */
    fun fileName(url: String): String {
        val raw = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrDefault(raw)
        return accents.replace(Normalizer.normalize(decoded.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
    }
}
