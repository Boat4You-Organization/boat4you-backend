package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.YachtImageSource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

@Entity
@Table(
    name = "yacht_image",
)
open class YachtImage {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Size(max = 255)
    @Column(name = "url")
    open var url: String? = null

    @Size(max = 255)
    @Column(name = "external_url")
    open var externalUrl: String? = null

    /**
     * Position in images array displayed on FE
     */
    @Column(name = "\"position\"")
    open var position: Short? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "yacht_id", nullable = false)
    open var yacht: Yacht? = null

    /**
     * From Nausys only.
     */
    @Column(name = "main_image")
    open var mainImage: Boolean? = null

    @ColumnDefault("false")
    @Column(name = "synced")
    open var synced: Boolean? = null

    /**
     * V9_91: never served to the public and never the main image - the partner's placeholder (NauSys blocked photo,
     * [hr.workspace.boat4you.domains.catalouge.utils.KnownPlaceholderImages]) or an admin decision. Read-only for
     * Hibernate: written only by SQL (image download, admin repair / PATCH), so a partner sync re-saving the row can
     * never clear it.
     */
    @Column(name = "hidden", insertable = false, updatable = false)
    open var hidden: Boolean = false

    /** V9_91: why [hidden] (PARTNER_BLOCKED_PHOTO, ADMIN). Admin-only, read-only for Hibernate like [hidden]. */
    @Column(name = "hidden_reason", columnDefinition = "TEXT", insertable = false, updatable = false)
    open var hiddenReason: String? = null

    /**
     * V9_91: null = the file name decides whether this is the layout drawing ([hr.workspace.boat4you.domains.catalouge.utils.LayoutImageRules]);
     * true / false = an admin or the visual layout detector said so. Read-only for Hibernate like [hidden].
     */
    @Column(name = "layout_override", insertable = false, updatable = false)
    open var layoutOverride: Boolean? = null

    /**
     * V9_92: PARTNER (sync rows, the default) or OWN (an admin upload): the partner syncs never delete, move below the
     * partner's or re-flag an OWN row. Set on INSERT, never updated (a sync re-saving an OWN row cannot turn it PARTNER).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false, updatable = false)
    open var source: YachtImageSource = YachtImageSource.PARTNER
}
