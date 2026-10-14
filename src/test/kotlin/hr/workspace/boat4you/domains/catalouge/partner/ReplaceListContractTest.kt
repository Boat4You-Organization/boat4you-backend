package hr.workspace.boat4you.domains.catalouge.partner

import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The curated R01 lists (DESIGN WS-R01, revision 2): replace.txt only names real partners (operators.txt or a reviewed
 * alias), never an allow-listed / ordinary / builder word, and no single word of it is a boat or a place name; the V9_83
 * SQL carries exactly the frozen replace.v9_83.txt, which is still a subset of replace.txt.
 *
 * Fixtures under src/test/resources/partner come from the rehearsal copy (see each file's header: the lead refreshes them
 * from cusma4 read-only before wave 1).
 */
class ReplaceListContractTest {
    private val replace = PartnerCopySanitizer.replaceEntries
    private val operators = CapacityFixtures.operators.first.toSet()
    private val allowList = CapacityFixtures.operators.second.toSet()

    private fun fixture(name: String): Set<String> =
        ClassPathResource("partner/$name")
            .inputStream
            .bufferedReader()
            .readLines()
            .filterNot { it.startsWith("#") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private val aliases: Map<String, String> =
        PartnerCopySanitizer.readLines(PartnerCopySanitizer.ALIASES_RESOURCE).associate {
            it.substringBefore('=').trim() to it.substringAfter('=').trim()
        }

    private val neverReplace = PartnerCopySanitizer.readLines(PartnerCopySanitizer.NEVER_REPLACE_RESOURCE)

    @Test
    fun `every replace entry is a partner of operators txt or a reviewed alias of one`() {
        replace.forEach { e ->
            val target = aliases[e.name]
            val ok =
                when {
                    e.name in operators -> true
                    target == null -> false
                    target.startsWith("agency:") -> target.removePrefix("agency:") in CapacityFixtures.agencySeedNames
                    else -> target in operators
                }
            assertTrue(ok, "replace.txt '${e.name}' is neither on operators.txt nor a valid alias (target=$target)")
        }
    }

    @Test
    fun `no replace entry is on the allow-list, on never_replace or a builder or model word`() {
        val builderWords = fixture("builder_model_words.txt")
        replace.forEach { e ->
            assertTrue(e.name !in allowList, "'${e.name}' is on the operators.txt '!' allow-list")
            assertTrue(e.name !in neverReplace, "'${e.name}' is on never_replace.txt")
            assertTrue(e.name.lowercase() !in builderWords, "'${e.name}' is a builder / model first word")
        }
    }

    @Test
    fun `no single-word entry that fires on its own equals a yacht name or a location-name token`() {
        val yachtNames = fixture("yacht_names.txt")
        val locationTokens = fixture("location_tokens.txt")
        // "@model" entries ("Moorings") never fire on the bare word - only before a model number or 's - so the place
        // "The Moorings Marina" does not make them collide (that phrase is matched by the multi-word "The Moorings").
        replace.filter { !it.name.contains(' ') && !it.beforeModelOrPossessive }.forEach { e ->
            val folded = e.name.lowercase()
            assertTrue(folded !in yachtNames, "'${e.name}' is the name of a boat")
            assertTrue(folded !in locationTokens, "'${e.name}' is a word of a location name")
        }
        assertTrue("hermes" in yachtNames, "the fixture still shows why bare Hermes is not on the list")
        assertTrue(replace.none { it.name == "Hermes" || it.name == "Athenian" })
    }

    @Test
    fun `never_replace words and allow-listed names pass the sanitizer byte-identical`() {
        (neverReplace + neverReplace.map { w -> w.replaceFirstChar { it.uppercase() } } + allowList).forEach { word ->
            assertSame(word, PartnerCopySanitizer.extraName(word), "extraName($word)")
            assertSame(word, PartnerCopySanitizer.description(word), "description($word)")
        }
    }

    @Test
    fun `premises-only names are rewritten only inside a premises phrase`() {
        val premisesOnly = PartnerCopySanitizer.readLines(PartnerCopySanitizer.PREMISES_RESOURCE).toSet() - replace.map { it.name }.toSet()
        assertTrue(premisesOnly.containsAll(listOf("Athenian", "Hermes")))
        premisesOnly.forEach { name ->
            val alone = "Day trip with $name"
            assertSame(alone, PartnerCopySanitizer.description(alone), "$name alone is untouched")
            assertEquals("Fees at the charter base pier", PartnerCopySanitizer.description("Fees at $name's Pier"), "$name in P1")
        }
    }

    @Test
    fun `the V9_83 alternation equals the frozen snapshot, which is a subset of replace txt`() {
        val snapshot = PartnerCopySanitizer.parseEntries(PartnerCopySanitizer.readLines(PartnerCopySanitizer.REPLACE_V9_83_RESOURCE))
        assertTrue(snapshot.isNotEmpty())
        assertTrue(replace.containsAll(snapshot), "replace.v9_83.txt must stay a subset of replace.txt: ${snapshot - replace.toSet()}")

        val expected =
            PartnerCopySanitizer.parseEntries(PartnerCopySanitizer.readLines(PartnerCopySanitizer.REPLACE_V9_83_RESOURCE), withCapitals = true).joinToString("|") { e ->
                e.name + if (e.beforeModelOrPossessive) "(?=[[:space:]]*[0-9]|[''’]s\\M)" else ""
            }
        val sql = ClassPathResource(R01MigrationTest.MIGRATION).inputStream.bufferedReader().readText()
        val n3 = Regex("""confirmed by \((.*?)\)\( office\)\?', '\\1o be confirmed by Boat4You'""").find(sql)?.groupValues?.get(1)
        val n4 = Regex("""'\\m\((.*?)\)\\M', 'Boat4You'""").find(sql)?.groupValues?.get(1)
        assertEquals(expected, n3, "N3 alternation in V9_83")
        assertEquals(expected, n4, "N4 alternation in V9_83")

        // the rows V9_83 leaves to Kotlin because an "@proper" entry is used as an ordinary word name exactly those entries
        val proper = snapshot.filter { it.properNounOnly }.joinToString("|") { it.name }
        val wordRe = Regex("""word_re constant text := '\(\^\|\[\.!\?:\\n\]\)\[ \\t\]\*\((.*?)\)\[""").find(sql)?.groupValues?.get(1)
        assertTrue(proper.isNotEmpty())
        assertEquals(proper, wordRe, "word_re in V9_83")
    }
}
