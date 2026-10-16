package hr.workspace.boat4you.domains.catalouge.utils

import java.security.MessageDigest

/**
 * Pictures a partner serves in place of the real photo - never a photo of the boat (owner incident 16.10.2026, FuFu
 * 4345: "Picture is not shown due to possible legal issues" as the boat page's main image). NauSys answers some MODEL
 * pictures (rest/yachtModel/{id}/pictures/main.jpg, flagged mainPicture) with 307 to a generic blockedPhoto.png
 * (PNG 1930x1306, 72,615 bytes); we converted it to WebP and stored it like a photo (21 files on 11 active boats on
 * 16.10.2026, every one byte-identical: 15,002 bytes).
 *
 * Recognised by the final URL after redirects ([BLOCKED_URL]) or by the SHA-256 of the bytes: [SOURCE_SHA256] for what
 * the partner sends (the download), [STORED_SHA256] for what we stored from it (the repair of files already on disk,
 * pre-filtered by [STORED_SIZES] so the scan stats files and hashes only the few of that exact size).
 */
object KnownPlaceholderImages {
    /** yacht_image.hidden_reason of an image hidden because the partner withholds the photo. */
    const val PARTNER_BLOCKED_PHOTO = "PARTNER_BLOCKED_PHOTO"

    /** The partner's generic "photo blocked" image, wherever it is hosted. */
    val BLOCKED_URL = Regex("""(?i)GenericImage/.*/blockedPhoto""")

    /** SHA-256 (lowercase hex) of the bytes the partner serves. */
    val SOURCE_SHA256: Set<String> =
        setOf(
            "a9c83ba43cd4c9236c42c3ca2f0804d1deb65ca9cf778954e50edbe4c5661f25", // NauSys GenericImage/ORIGINAL/blockedPhoto.png, 72,615 bytes
        )

    /** SHA-256 (lowercase hex) of our stored WebP of a [SOURCE_SHA256] image (ImageUtils.convertToWebP). */
    val STORED_SHA256: Set<String> =
        setOf(
            "7e9b3607b582cf916fcc00b46f1c932a522596fe676a06ae023cde1fb851c5ae", // NauSys blockedPhoto.png as stored, 1930x1306
        )

    /** Byte sizes of the [STORED_SHA256] files: a stored file of another size is not hashed at all. */
    val STORED_SIZES: Set<Long> = setOf(15_002L)

    /** The downloaded picture is a placeholder: its final URL, or the partner's bytes. */
    fun isPlaceholder(
        finalUrl: String?,
        sourceBytes: ByteArray,
    ): Boolean = (finalUrl != null && BLOCKED_URL.containsMatchIn(finalUrl)) || sha256(sourceBytes) in SOURCE_SHA256

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
