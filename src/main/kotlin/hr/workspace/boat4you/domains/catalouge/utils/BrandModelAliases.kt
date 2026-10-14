package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum

/**
 * R02 model guards (14.10.2026), so the partner syncs never bring a charter brand back into a model or a slug:
 *
 * - G3 [aliasFor]: a charter brand's model label ("Sunsail 424") resolves to the builder model id it really is
 *   (`partner/brand_model_alias.txt`, seeded from the CERTAIN V9_84 pairs). Used only when a partner model id has no
 *   mapping yet; the caller checks the target row's name ([matchesTarget]) and saves the mapping to it - the target is
 *   never created nor overwritten by the partner.
 * - G5 [isBrandManufacturer]: a charter brand (Sunsail, The Moorings, NSS) is never set as the manufacturer of a model
 *   (SlugUtils would prefix "sunsail-" to every slug of that model).
 * - [isBrandLabel]: an unknown brand label is still created as the partner sends it (owner flag F3: uncertain labels are
 *   not renamed in this wave), with a WARN so it can be added to the alias file.
 *
 * G4 lives in ManufacturerAliasResolver: "Sunsail" / "The Moorings" are never aliased to one builder (their fleets span
 * Leopard, Jeanneau, Beneteau, Dufour, Lagoon).
 */
object BrandModelAliases {
    const val RESOURCE = "partner/brand_model_alias.txt"

    data class Alias(
        val system: ExternalSystemEnum,
        val label: String,
        val targetModelId: Long,
        val targetModelName: String,
    )

    private val WHITESPACE = Regex("\\s+")
    private val BRAND_MANUFACTURERS = setOf("sunsail", "the moorings", "moorings", "nss", "nss charter")
    private val BRAND_LABEL_FIRST_WORDS = setOf("sunsail", "moorings", "nss", "dream")

    private val shipped: List<Alias> by lazy { parse(load()) }

    /** Tests only: replaces the shipped file (a NauSys entry does not exist yet). */
    @Volatile
    internal var overrideForTests: List<Alias>? = null

    val aliases: List<Alias>
        get() = overrideForTests ?: shipped

    fun aliasFor(
        system: ExternalSystemEnum,
        partnerModelLabel: String?,
    ): Alias? {
        val key = fold(partnerModelLabel ?: return null)
        return aliases.firstOrNull { it.system == system && it.label == key }
    }

    /** The target row still carries the name the file expects (a renamed or re-used id is never aliased). */
    fun matchesTarget(
        alias: Alias,
        modelName: String?,
    ): Boolean = modelName != null && fold(modelName) == fold(alias.targetModelName)

    fun isBrandManufacturer(name: String?): Boolean = name != null && fold(name) in BRAND_MANUFACTURERS

    fun isBrandLabel(label: String?): Boolean = label != null && fold(label).substringBefore(' ') in BRAND_LABEL_FIRST_WORDS

    fun fold(value: String): String = value.lowercase().replace(WHITESPACE, " ").trim()

    internal fun parse(lines: List<String>): List<Alias> =
        lines
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split('|').map { it.trim() }
                require(parts.size == 4) { "$RESOURCE: expected system|label|target id|target name, got '$line'" }
                Alias(
                    system = ExternalSystemEnum.valueOf(parts[0]),
                    label = fold(parts[1]),
                    targetModelId = parts[2].toLong(),
                    targetModelName = parts[3],
                )
            }

    private fun load(): List<String> {
        val stream =
            BrandModelAliases::class.java.classLoader.getResourceAsStream(RESOURCE)
                ?: error("$RESOURCE missing from the classpath")
        return stream.bufferedReader().readLines()
    }
}
