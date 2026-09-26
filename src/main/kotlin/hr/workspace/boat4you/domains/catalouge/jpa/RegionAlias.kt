package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * Another spelling of a region: a partner's current name for the area (MMK / NAUSYS) or a name the region carried
 * before its name became canonical (HISTORIC, V9_68). `region.name` is the landing key and is set only when a sync
 * creates the region; every partner rename lands here instead (26.9.2026 audit B01).
 */
@Entity
@Table(name = "region_alias")
open class RegionAlias {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "region_id", nullable = false)
    open var regionId: Int? = null

    @Column(name = "alias", nullable = false, length = 100)
    open var alias: String? = null

    @Column(name = "source", nullable = false, length = 16)
    open var source: String? = null

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    open var createdAt: OffsetDateTime? = null

    @Column(name = "last_seen_at", nullable = false, insertable = false, updatable = false)
    open var lastSeenAt: OffsetDateTime? = null
}
