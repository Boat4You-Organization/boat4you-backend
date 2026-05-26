package hr.workspace.boat4you.domains.catalouge.utils

object SlugUtils {
    fun toSlugWithId(
        manufacturerName: String?,
        modelName: String?,
        yachtName: String?,
        yachtId: Long,
    ): String {
        // Manufacturers usually prefix the model name in our catalogue
        // (manufacturer "Lagoon" + model "Lagoon 40", or "Bali" + "Bali 4.6")
        // because partner feeds bake the brand into the model label.
        // Concatenating both raw produced slugs like
        // `lagoon-lagoon-40-af-lag40an-5201` — duplicate brand token, ugly
        // in SERPs and trips Ahrefs/Screaming Frog "duplicate token in URL"
        // checks. Drop the manufacturer when the model already carries it.
        val manuTrimmed = manufacturerName?.trim().orEmpty()
        val modelTrimmed = modelName?.trim().orEmpty()
        val modelHasManuPrefix =
            manuTrimmed.isNotBlank() &&
                modelTrimmed.lowercase().startsWith("${manuTrimmed.lowercase()} ") ||
                modelTrimmed.equals(manuTrimmed, ignoreCase = true)

        val rawParts =
            listOfNotNull(
                manufacturerName.takeUnless { modelHasManuPrefix },
                modelName,
                yachtName,
            ).filter { it.isNotBlank() }

        val slugPart =
            if (rawParts.isNotEmpty()) {
                toSlug(rawParts.joinToString("-"))
            } else {
                ""
            }

        return if (slugPart.isNotEmpty()) {
            "$slugPart-$yachtId"
        } else {
            yachtId.toString()
        }
    }

    fun toSlug(input: String): String {
        return input
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "") // Remove non-alphanumeric characters except spaces and hyphens
            .trim()
            .replace(Regex("\\s+"), "-") // Replace spaces with hyphens
            .replace(Regex("-+"), "-") // Replace multiple hyphens with a single hyphen
    }

    fun idFromSlug(slug: String): Long? {
        return slug.split("-").lastOrNull()?.toLongOrNull()
    }
}
