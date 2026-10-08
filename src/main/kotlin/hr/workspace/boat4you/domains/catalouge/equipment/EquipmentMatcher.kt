package hr.workspace.boat4you.domains.catalouge.equipment

import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import org.apache.commons.text.similarity.LevenshteinDistance

/**
 * Links a partner equipment name to our equipment catalogue (equipment audit 8.10.2026, FIX_CONTRACT 6). Equipment only:
 * extras keep Matchers.extrasNameMatch.
 *
 * Replaces "first row of findAll() whose keys pass Jaro-Winkler >= 0.9": that let compound prefixes through
 * (lifebuoy ~ life, watermaker ~ water, anchorage ~ anchor) and the physical row order picked the code. Here the BEST hit
 * over all rows wins, deterministically:
 * - tokens are equal, a plural (s / es, stem >= 3 chars) or, for positive keys only, a typo: both >= 6 chars, same first
 *   letter, Levenshtein distance 1. Any other prefix is no match;
 * - per row the best key counts, rows compare by (exact name, key tokens, first matched position in the name, weakest
 *   token similarity, key length); a full tie goes to the lower id. "Fridge on flybridge" -> fridge,
 *   "Chart plotter in cockpit" -> outside-GPS-plotter;
 * - alias rows (merged_into_id) are never candidates; names that are empty, longer than 70 chars or start with
 *   no / without / not never link.
 * Keys are parsed once per catalogue load (the matcher is rebuilt only when equipmentCache hands out a new list).
 * Faithful to sim/Sim.java of the audit; EquipmentMatcherGoldenTest pins all 2,778 partner names of the prod snapshot.
 */
class EquipmentMatcher(
    equipment: List<Equipment>,
) {
    private val candidates: List<Candidate> =
        equipment
            .filter { it.id != null && it.mergedIntoId == null }
            .sortedBy { it.id }
            .map { Candidate(it, parseKeys(it.matchKeys)) }

    /** The best catalogue row for [name], or null when nothing matches (or a key vetoes every hit). */
    fun best(name: String?): Equipment? {
        val partnerName = PartnerName.of(name) ?: return null
        var best: Equipment? = null
        var bestScore: Score? = null
        candidates.forEach { candidate ->
            val score = candidate.score(partnerName) ?: return@forEach
            if (bestScore == null || score > bestScore!!) {
                best = candidate.equipment
                bestScore = score
            }
        }
        return best
    }

    /** Lexicographic rank of one key hit: exact name, key tokens, earlier position, similarity, key length. */
    data class Score(
        val exact: Int,
        val keyTokens: Int,
        val firstPosition: Int,
        val similarity: Double,
        val keyChars: Int,
    ) : Comparable<Score> {
        override fun compareTo(other: Score): Int = compareValuesBy(this, other, { it.exact }, { it.keyTokens }, { -it.firstPosition }, { it.similarity }, { it.keyChars })
    }

    private class Candidate(
        val equipment: Equipment,
        val keys: List<Key>,
    ) {
        private val vetoes = keys.filterIsInstance<Key.Veto>()
        private val positives = keys.filterNot { it is Key.Veto }

        fun score(name: PartnerName): Score? {
            if (vetoes.any { it.vetoes(name) }) return null
            var best: Score? = null
            positives.forEach { key ->
                val hit = key.score(name) ?: return@forEach
                if (best == null || hit > best!!) best = hit
            }
            return best
        }
    }

    /** A partner name prepared once for every key of every row. */
    class PartnerName private constructor(
        val raw: String,
        val normalized: String,
        val tokens: List<String>,
    ) {
        /** Raw-case tokens for `case:` keys. */
        val caseTokens: List<String> by lazy { javaTrim(raw.replace(NOT_LETTER_OR_DIGIT, " ")).split(" ") }

        companion object {
            fun of(name: String?): PartnerName? {
                if (name.isNullOrBlank() || name.length > MAX_NAME_LENGTH) return null
                val tokens = EquipmentNames.tokens(name)
                if (tokens.firstOrNull() in NEGATIONS) return null
                return PartnerName(name, EquipmentNames.normalize(name), tokens)
            }
        }
    }

    sealed class Key {
        /** null = no hit. */
        open fun score(name: PartnerName): Score? = null

        /** `not:` - substring of the raw name (any case) or the tokens consecutive in the name, without typo tolerance. */
        class Veto(
            private val raw: String,
        ) : Key() {
            private val normalized = EquipmentNames.normalize(raw)
            private val tokens = EquipmentNames.tokens(raw)

            fun vetoes(name: PartnerName): Boolean =
                name.raw.contains(raw, ignoreCase = true) ||
                    (normalized.isNotEmpty() && " ${name.normalized} ".contains(" $normalized ")) ||
                    consecutive(tokens, name.tokens)
        }

        /** `full-match:` - the normalised name equals the normalised phrase. */
        class Full(
            raw: String,
        ) : Key() {
            private val normalized = EquipmentNames.normalize(raw)
            private val tokenCount = EquipmentNames.tokens(raw).size

            override fun score(name: PartnerName): Score? = if (normalized == name.normalized) Score(1, tokenCount, 0, 1.0, normalized.length) else null
        }

        /** `token-match:` (and a key without prefix) - every token of the phrase anywhere in the name. */
        class Tokens(
            raw: String,
        ) : Key() {
            private val normalized = EquipmentNames.normalize(raw)
            private val tokens = EquipmentNames.tokens(raw)

            override fun score(name: PartnerName): Score? {
                if (tokens.isEmpty()) return null
                var weakest = 1.0
                var firstPosition = Int.MAX_VALUE
                tokens.forEach { keyToken ->
                    var bestSimilarity = 0.0
                    var bestPosition = -1
                    name.tokens.forEachIndexed { position, nameToken ->
                        val similarity = tokenSimilarity(keyToken, nameToken, typos = true)
                        if (similarity > bestSimilarity) {
                            bestSimilarity = similarity
                            bestPosition = position
                        }
                    }
                    if (bestSimilarity == 0.0) return null
                    weakest = minOf(weakest, bestSimilarity)
                    firstPosition = minOf(firstPosition, bestPosition)
                }
                return Score(if (normalized == name.normalized) 1 else 0, tokens.size, firstPosition, weakest, normalized.length)
            }
        }

        /** `case:` - the exact, case-sensitive token(s) of the raw name (case:SUP). */
        class CaseTokens(
            private val raw: String,
        ) : Key() {
            private val parts = raw.split(" ")

            override fun score(name: PartnerName): Score? {
                val index = indexOfSubList(name.caseTokens, parts)
                if (index < 0) return null
                return Score(if (raw == javaTrim(name.raw)) 1 else 0, parts.size, index, 1.0, raw.length)
            }
        }

        /** `case-substring:` - case-sensitive substring of the raw name (kept for compatibility, unused by R__1_05 v2). */
        class CaseSubstring(
            private val raw: String,
        ) : Key() {
            private val normalized = EquipmentNames.normalize(raw)
            private val tokenCount = EquipmentNames.tokens(raw).size

            override fun score(name: PartnerName): Score? {
                val index = name.raw.indexOf(raw)
                if (index < 0) return null
                val position = EquipmentNames.tokens(name.raw.substring(0, index)).size
                return Score(if (normalized == name.normalized) 1 else 0, tokenCount, position, 1.0, normalized.length)
            }
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 70
        private val NEGATIONS = setOf("no", "without", "not")
        private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")
        private val LEVENSHTEIN_1 = LevenshteinDistance(1)
        private const val PLURAL = 0.99
        private const val TYPO = 0.9
        private const val MIN_PLURAL_STEM = 3
        private const val MIN_TYPO_LENGTH = 6

        /** match_keys "a, token-match:b c, not:d" -> keys; blank entries ('' on alias rows) are skipped. */
        fun parseKeys(matchKeys: String?): List<Key> =
            (matchKeys ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .mapNotNull { key ->
                    when {
                        key.startsWith("not:") -> key.substring(4).trim().takeIf { it.isNotEmpty() }?.let { Key.Veto(it) }
                        key.startsWith("full-match:") -> Key.Full(key.substring(11).trim())
                        key.startsWith("case-substring:") -> Key.CaseSubstring(key.substring(15))
                        key.startsWith("token-match:") -> Key.Tokens(key.substring(12).trim())
                        key.startsWith("case:") -> Key.CaseTokens(key.substring(5).trim())
                        else -> Key.Tokens(key)
                    }
                }

        /**
         * 1 = equal, 0.99 = plural (s / es on a stem of >= 3 chars), 0.9 = typo (positive keys only: both >= 6 chars,
         * same first letter, Levenshtein 1); 0 otherwise, including every other prefix (lifebuoy / life).
         */
        fun tokenSimilarity(
            keyToken: String,
            nameToken: String,
            typos: Boolean,
        ): Double {
            if (keyToken == nameToken) return 1.0
            val shorter = if (keyToken.length <= nameToken.length) keyToken else nameToken
            val longer = if (keyToken.length <= nameToken.length) nameToken else keyToken
            if (longer.startsWith(shorter)) {
                val rest = longer.substring(shorter.length)
                return if (shorter.length >= MIN_PLURAL_STEM && (rest == "s" || rest == "es")) PLURAL else 0.0
            }
            if (!typos || shorter.length < MIN_TYPO_LENGTH || keyToken[0] != nameToken[0]) return 0.0
            val distance = LEVENSHTEIN_1.apply(keyToken, nameToken)
            return if (distance != null && distance in 0..1) TYPO else 0.0
        }

        private fun consecutive(
            keyTokens: List<String>,
            nameTokens: List<String>,
        ): Boolean {
            if (keyTokens.isEmpty()) return false
            for (start in 0..nameTokens.size - keyTokens.size) {
                if (keyTokens.indices.all { tokenSimilarity(keyTokens[it], nameTokens[start + it], typos = false) > 0.0 }) return true
            }
            return false
        }

        private fun indexOfSubList(
            source: List<String>,
            target: List<String>,
        ): Int = java.util.Collections.indexOfSubList(source, target)

        /** String.trim() of Java (chars <= U+0020), as the audit simulation. */
        private fun javaTrim(value: String): String = value.trim { it <= ' ' }
    }
}
