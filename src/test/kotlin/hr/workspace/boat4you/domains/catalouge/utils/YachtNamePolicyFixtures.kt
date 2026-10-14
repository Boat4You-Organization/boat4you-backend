package hr.workspace.boat4you.domains.catalouge.utils

/** Raw partner yacht name -> public name (R02). Shared by YachtNamePolicyTest and the SQL parity test (V9_87). */
object YachtNamePolicyFixtures {
    val CASES: List<Pair<String, String>> =
        listOf(
            // fleet labels (flag F1 = literal "Boat4You")
            "Sunsail 424/4/4 Premium" to "Boat4You 424/4/4 Premium",
            "Sunsail 424/4/4 Premium Plus" to "Boat4You 424/4/4 Premium Plus",
            "Sunsail 44 SO Classic" to "Boat4You 44 SO Classic",
            "Moorings 4500 Club" to "Boat4You 4500 Club",
            "Moorings 42.3 Club" to "Boat4You 42.3 Club",
            "Moorings Oceanis 30.1 Club" to "Boat4You Oceanis 30.1 Club",
            "Sunsail/Moorings 41.3" to "Boat4You 41.3",
            "Sunsail / Moorings 41.3 Premium" to "Boat4You 41.3 Premium",
            "The Moorings 5000" to "Boat4You 5000",
            "NSS Charter 45" to "Boat4You 45",
            "NSS Bavaria 46" to "Boat4You Bavaria 46",
            "Navigare 46" to "Boat4You 46",
            "Sunsail  41.3   Premium" to "Boat4You 41.3 Premium",
            // curated agency trade names (flag F4), whole name only, folded
            "Rhodes Yachting" to "Boat4You",
            "rhodes  yachting " to "Boat4You",
            "Rhódes Yachting" to "Boat4You",
            "Active Cruises" to "Boat4You",
            // never renamed: owner-named boats, borderline agency names, ordinary words, other cases
            "Zenith" to "Zenith",
            "Despina" to "Despina",
            "Athena Blu" to "Athena Blu",
            "Hermes" to "Hermes",
            "Ionian Spirit" to "Ionian Spirit",
            "Omnia" to "Omnia",
            "Sailtours" to "Sailtours",
            "Trapani Vela Club" to "Trapani Vela Club",
            "Rhodes Yachting II" to "Rhodes Yachting II",
            "Sunsailor" to "Sunsailor",
            "Lazy Moorings" to "Lazy Moorings",
            "Moorings4500" to "Moorings4500",
            "SUNSAIL 41" to "SUNSAIL 41",
            "Gulet" to "Gulet",
            "Šime  " to "Šime  ",
            "My Lagoon" to "My Lagoon",
            // already public: idempotent
            "Boat4You 424/4/4 Premium" to "Boat4You 424/4/4 Premium",
            "Boat4You" to "Boat4You",
        )
}
