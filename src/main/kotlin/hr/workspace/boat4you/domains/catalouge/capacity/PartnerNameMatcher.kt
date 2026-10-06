package hr.workspace.boat4you.domains.catalouge.capacity

import java.text.Normalizer

/**
 * Whole-word match of charter-operator and agency names in partner free text (capacity contract v1, section 6). The
 * names are folded like b4y's operatorNames.ts (lower case, no diacritics, & -> and, punctuation -> one blank; letters
 * of every script are kept, so a Greek or Cyrillic name is matched too) and the text is matched word n-gram by word
 * n-gram, so "Navigare Yachting d.o.o." also catches "navigare yachting" and the brand alone, "navigare", and a name is
 * never found inside another word.
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
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

        /** Whole names shorter than this are not matched ("TYC", "NCC" are). */
        private const val MIN_NAME = 3

        /** The name without its legal form: at least this long. */
        private const val MIN_WITHOUT_LEGAL_FORM = 4

        /** The brand alone (trade words dropped): at least this long. */
        private const val MIN_BRAND = 5

        /** Legal forms dropped from a name to also catch the short form. */
        private val LEGAL_SUFFIX =
            Regex(
                "\\s+(?:d o o|j d o o|doo|ltd|limited|llc|inc|s r l|srl|s l|sl|s a|sa|gmbh|ag|ike|e e|oe|epe|sas|sarl|bv|b v|" +
                    "kg|ohg|ug)$",
            )

        /** Trade words dropped from the end of a name to also catch the brand alone ("Pitter Yachtcharter" -> "pitter"). */
        private val TRADE_SUFFIX =
            Regex("\\s+(?:yachting|yachts|yacht charters?|yachtcharter|charters?|sailing|nautika|nautica|travel)$")

        /**
         * Words a capacity note or an engine / sail label legitimately uses (layout, numbers, boat parts, sails, engines).
         * A whole name that is one of them, or a short form made only of them, is skipped: "Master Yachting", "Seven
         * Charter", "Starboard Charter" would otherwise hide "(4+1 master cabin)", "seven berths", "starboard bow cabin".
         * Words that are an operator's brand on their own (mainsail, genoa, lagoon, luxury ...) are left out on purpose:
         * a note naming the operator must stay hidden, a note merely using the word is hidden too (the number shows).
         */
        private val CAPACITY_VOCABULARY: Set<String> =
            (
                "crew skipper skippers hostess saloon salon cabin cabins double doubles single bunk bunks bed beds forepeak bow " +
                    "front box guest guests pax people persons berth berths wc toilet toilets head heads shower showers central big " +
                    "convertible sofa ensuite plus for the in at no one of with recommended number " +
                    "and or on per max min extra additional separate private master family twin queen king large small " +
                    "children child kids adults adult cook chef stewardess deckhand captain bathroom bathrooms sink electric manual " +
                    "standard comfort spacious " +
                    "two three four five six seven eight nine ten eleven twelve first second third " +
                    "aft stern forward fwd starboard port portside hull hulls deck cockpit peak bunkroom " +
                    "catamaran catamarans monohull trimaran yacht boat sailboat gulet motor power " +
                    "main jib headsail staysail batten battens full semi half furling roller roll classic self tacking gennaker " +
                    "spinnaker code reefing boom mast " +
                    "engine engines diesel inboard outboard saildrive shaft yanmar volvo penta nanni perkins lombardini mercury " +
                    "suzuki honda yamaha tohatsu caterpillar cummins man mtu hp kw bhp ps cv"
            ).split(' ').toSet()

        /** b4y operatorNames.ts fold, with letters of every script kept (NFKD also folds fullwidth / compatibility forms). */
        fun fold(value: String): String {
            val lowered = value.lowercase().replace('đ', 'd').replace('ł', 'l')
            return Normalizer
                .normalize(lowered, Normalizer.Form.NFKD)
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

        /**
         * Folded names to match: every operators.txt name (minus the allow-list) and agency name in full, without its
         * legal form, and as the brand alone. An allow-listed name gives no short form either.
         */
        fun of(
            operatorNames: Collection<String>,
            allow: Collection<String>,
            agencyNames: Collection<String>,
        ): PartnerNameMatcher {
            val allowed = allow.map { fold(it) }.toSet()
            val out = HashSet<String>()

            fun addShortForm(
                folded: String,
                minLength: Int,
            ) {
                if (folded.length >= minLength && !folded.split(' ').all { it in CAPACITY_VOCABULARY }) out += folded
            }
            (operatorNames + agencyNames).forEach { name ->
                val full = fold(name)
                if (full in allowed) return@forEach
                if (full.length >= MIN_NAME && full !in CAPACITY_VOCABULARY) out += full
                var short = full.replaceFirst(LEGAL_SUFFIX, "").trim()
                if (short in allowed) return@forEach
                if (short != full) addShortForm(short, MIN_WITHOUT_LEGAL_FORM)
                while (true) {
                    val brand = short.replaceFirst(TRADE_SUFFIX, "").trim()
                    if (brand == short || brand in allowed) break
                    addShortForm(brand, MIN_BRAND)
                    short = brand
                }
            }
            return PartnerNameMatcher(out)
        }
    }
}
