package hr.workspace.boat4you.domains.catalouge.capacity

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

/**
 * Total engine horsepower for the engine-power FILTER (yacht.engine_power) from a partner engine string (capacity
 * contract v1, 5.3), replacing `extractAndMultiplyNumbers`, which multiplied any two numbers it found ("Volvo MD 22
 * Saildrive 40 h.p." = 880 hp, "33.12Kw" = 396 hp). Reads `<count x>? <brand word>? <number> <brand word>? <unit>`;
 * anything ambiguous is null rather than a guess: no unit ("2x 45", "27.3", "Volvo Penta D2-75"), two different powers,
 * a count outside 1-4 or a per-engine power outside 1-3000 hp. kW are converted with the metric horsepower marine
 * makers quote (33.12 kW = 45 hp, Yanmar 4JH45). The same match decides whether the raw MMK label is shown at all: only
 * when it carries a power with a unit.
 */
object EnginePowerParser {
    /** kW -> hp (PS), as Yanmar / Volvo quote it: 33.12 kW = 45, 41.9 = 57, 58.8 = 80. */
    const val KW_TO_HP = 1.35962

    private const val MAX_COUNT = 4
    private const val MAX_HP_EACH = 3000.0
    private const val MAX_NAUSYS_TOTAL = 10000

    /** Greek / Cyrillic capitals that look Latin ("Volvo 75 ΗΡ" in the sample). */
    private val CONFUSABLES: Map<Char, Char> =
        ("ΑΒΕΗΙΚΜΝΟΡΤΥΧΖ" + "АВЕКМНОРСТХаеорсх").zip("ABEHIKMNOPTYXZ" + "ABEKMHOPCTXaeopcx").toMap()

    /**
     * The number must not follow a letter, digit, dot or hyphen, so model codes never count ("D2-75", the 22 of
     * "MD 22 Saildrive").
     */
    private val ENGINE_POWER =
        Regex(
            "(?<![\\p{L}\\p{N}.\\-])(?:(\\d)\\s*[x×*]\\s*(?:[a-z]+\\s+)?)?(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(?:[a-z]+\\s+)?" +
                "(b\\.?\\s?h\\.?\\s?p\\.?|h\\.\\s?p\\.?|hp|ps|cv|kw)(?![a-z])",
        )

    data class EngineMatch(
        val count: Int,
        val each: Double,
        val kw: Boolean,
        val totalHp: Long,
    ) {
        val eachHp: Double get() = if (kw) each * KW_TO_HP else each
    }

    fun normalize(raw: String?): String? {
        val note = CapacityText.normalizeNote(raw) ?: return null
        val nfkc = Normalizer.normalize(note, Normalizer.Form.NFKC)
        return buildString(nfkc.length) { nfkc.forEach { append(CONFUSABLES[it] ?: it) } }.lowercase()
    }

    fun matches(raw: String?): List<EngineMatch> {
        val s = normalize(raw) ?: return emptyList()
        return ENGINE_POWER
            .findAll(s)
            .map { m ->
                val count = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: 1
                val each = m.groupValues[2].replace(',', '.').toDouble()
                val kw = m.groupValues[3] == "kw"
                val eachHp = if (kw) each * KW_TO_HP else each
                // Math.round = half up, as the contract's reference (JavaScript Math.round); kotlin.math.round is half even
                EngineMatch(count = count, each = each, kw = kw, totalHp = Math.round(count * eachHp))
            }.toList()
    }

    /** True when the label carries a power with a unit: only then is the MMK engine label shown (critique C10). */
    fun hasUnit(raw: String?): Boolean = matches(raw).isNotEmpty()

    /** engine_power (total hp, filter value) from an MMK engine string, or null when ambiguous. */
    fun parseHp(raw: String?): Int? {
        val found = matches(raw)
        if (found.isEmpty() || found.map { it.totalHp }.toSet().size != 1) return null
        val m = found.first()
        if (m.count < 1 || m.count > MAX_COUNT || m.eachHp < 1.0 || m.eachHp > MAX_HP_EACH) return null
        return m.totalHp.toInt()
    }

    /** NauSys: engines x enginePower (per engine, hp - the unit is undocumented), rounded half up; no power -> null. */
    fun nausysTotalHp(
        engines: Int?,
        powerEach: BigDecimal?,
    ): Int? {
        if (powerEach == null || powerEach.signum() <= 0) return null
        val count = if (engines != null && engines > 0) engines else 1
        val total = powerEach.multiply(BigDecimal(count)).setScale(0, RoundingMode.HALF_UP)
        return if (total < BigDecimal.ONE || total > BigDecimal(MAX_NAUSYS_TOTAL)) null else total.toInt()
    }
}
