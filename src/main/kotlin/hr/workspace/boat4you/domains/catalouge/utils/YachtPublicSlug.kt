package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository

/**
 * The slug a boat's public URL answers under (owner rule 16.10.2026, "ovo mijenjanje URL-a ubija nas": it never changes
 * again). Every public slug - listings, boat page, twin canonical, successor, reservations, trip hub, inquiry e-mail -
 * comes from here, never from [SlugUtils] directly.
 *
 * The frozen `yacht.public_slug` (V9_90) wins; while it is NULL (not backfilled yet) the slug is computed exactly as
 * before, so nothing changes byte for byte until it is frozen. A stored slug that does not end in the boat's own id is
 * ignored: the sites resolve `/boat/{slug}` by the trailing id, so such a value would send the visitor to another boat.
 * [SlugUtils] stays the rule for computing a new slug: on creation ([freezeOnCreate]) and in the admin backfill / refresh.
 */
object YachtPublicSlug {
    fun of(yacht: Yacht): String =
        of(yacht.publicSlug, yacht.model?.manufacturer?.name, yacht.model?.name, yacht.name, yacht.id!!)

    /** For projections (search view, reservation view, native rows) that carry the stored slug next to the label parts. */
    fun of(
        publicSlug: String?,
        manufacturerName: String?,
        modelName: String?,
        yachtName: String?,
        yachtId: Long,
    ): String =
        publicSlug?.takeIf { SlugUtils.idFromSlug(it) == yachtId }
            ?: SlugUtils.toSlugWithId(manufacturerName, modelName, yachtName, yachtId)

    /** The frozen slugs of [ids] by yacht id, for list pages whose rows (views) do not carry the column: one lookup. */
    fun byId(
        yachtRepository: YachtRepository,
        ids: Collection<Long>,
    ): Map<Long, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            yachtRepository.findPublicSlugs(ids.toSet()).associate { (it[0] as Number).toLong() to it[1] as String }
        }

    /** The slug the current manufacturer, model and public name give - what gets frozen. */
    fun compute(yacht: Yacht): String =
        SlugUtils.toSlugWithId(yacht.model?.manufacturer?.name, yacht.model?.name, yacht.name, yacht.id!!)

    /**
     * A creation path (MMK / NauSys sync, admin custom yacht), after the INSERT gave the boat its id and its public name:
     * the slug it is born with becomes its URL. Only a NULL slug is ever written ([YachtRepository.freezePublicSlug]).
     */
    fun freezeOnCreate(
        yacht: Yacht,
        yachtRepository: YachtRepository,
    ) {
        val slug = compute(yacht)
        yachtRepository.freezePublicSlug(yacht.id!!, slug)
        yacht.publicSlug = yacht.publicSlug ?: slug
    }
}
