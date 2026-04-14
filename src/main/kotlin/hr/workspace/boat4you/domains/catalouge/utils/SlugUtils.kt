package hr.workspace.boat4you.domains.catalouge.utils

object SlugUtils {
    fun toSlugWithId(
        manufacturerName: String?,
        modelName: String?,
        yachtName: String?,
        yachtId: Long,
    ): String {
        val parts =
            listOfNotNull(manufacturerName, modelName, yachtName)
                .filter { it.isNotBlank() }

        val slugPart =
            if (parts.isNotEmpty()) {
                toSlug(parts.joinToString("-"))
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
