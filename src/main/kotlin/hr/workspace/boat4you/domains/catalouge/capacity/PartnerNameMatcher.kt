package hr.workspace.boat4you.domains.catalouge.capacity

import java.text.Normalizer

/**
 * Whole-word match of charter-operator and agency names in partner free text (capacity contract v1, section 6). The
 * names are folded like b4y's operatorNames.ts (lower case, no diacritics, & -> and, punctuation -> one blank) and the
 * text is matched word n-gram by word n-gram, so "Navigare Yachting d.o.o." also catches "navigare yachting" and a name
 * is never found inside another word.
 */
class PartnerNameMatcher private constructor(
    private val names: Set<String>,
) {
    private val maxWords: Int = names.maxOfOrNull { it.split(' ').size }?.coerceAtLeast(1) ?: 1

    val size: Int get() = names.size

    /** True when the text names an operator or agency. */
    fun matches(text: String): Boolean {
        if (names.isEmpty()) return false
        val words = fold(text).split(' ').filter { it.isNotEmpty() }
        for (start in words.indices) {
            var phrase = ""
            for (len in 1..maxWords) {
                if (start + len > words.size) break
                phrase = if (len == 1) words[start] else phrase + " " + words[start + len - 1]
                if (phrase in names) return true
            }
        }
        return false
    }

    companion object {
        private val MARKS = Regex("\\p{M}+")
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        /** Legal forms dropped from agency names to also catch the short form. */
        private val LEGAL_SUFFIX =
            Regex(
                "\\s+(?:d o o|j d o o|doo|ltd|limited|llc|inc|s r l|srl|s l|sl|s a|sa|gmbh|ag|ike|e e|oe|epe|sas|sarl|bv|b v|" +
                    "kg|ohg|ug)$",
            )

        /** Words a capacity note legitimately uses: an operator / agency whose folded name is one of them is skipped. */
        private val CAPACITY_VOCABULARY: Set<String> =
            (
                "crew skipper skippers hostess saloon salon cabin cabins double doubles single bunk bunks bed beds forepeak bow " +
                    "front box guest guests pax people persons berth berths wc toilet toilets head heads shower showers central big " +
                    "convertible sofa ensuite plus for the in at no one of with recommended number"
            ).split(' ').toSet()

        /** b4y operatorNames.ts fold. */
        fun fold(value: String): String {
            val lowered = value.lowercase().replace('đ', 'd').replace('ł', 'l')
            return Normalizer
                .normalize(lowered, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .replace("&", " and ")
                .replace(NON_ALNUM, " ")
                .trim()
        }

        /** operators.txt: one name per line, '#' comments, '!Name' = allow-list (never matched). */
        fun parseOperators(text: String): Pair<List<String>, List<String>> {
            val names = mutableListOf<String>()
            val allow = mutableListOf<String>()
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach {
                if (it.startsWith("!")) allow += it.substring(1).trim() else names += it
            }
            return names to allow
        }

        /** Folded names to match: operators.txt names (minus the allow-list) + agency names and their short forms. */
        fun of(
            operatorNames: Collection<String>,
            allow: Collection<String>,
            agencyNames: Collection<String>,
        ): PartnerNameMatcher {
            val allowed = allow.map { fold(it) }.toSet()
            val out = HashSet<String>()

            fun add(name: String) {
                val f = fold(name)
                if (f.length >= 3 && f !in allowed && f !in CAPACITY_VOCABULARY) out += f
            }
            operatorNames.forEach { add(it) }
            agencyNames.forEach { name ->
                val f = fold(name)
                if (f.length >= 4) add(f)
                val short = f.replaceFirst(LEGAL_SUFFIX, "").trim()
                if (short != f && short.length >= 4) add(short)
            }
            return PartnerNameMatcher(out)
        }
    }
}
