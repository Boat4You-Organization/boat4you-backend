package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

@Entity
@Table(name = "yacht_equipment")
open class YachtEquipment {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "equipment_id")
    open var equipment: Equipment? = null

    @Column(name = "equipment_id", insertable = false, updatable = false)
    var equipmentId: Long? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "yacht_id", nullable = false)
    open var yacht: Yacht? = null

    @Column(name = "yacht_id", insertable = false, updatable = false)
    var yachtId: Long? = null

    @Column(name = "name", length = Integer.MAX_VALUE)
    open var name: String? = null

    @Column(name = "external_id")
    open var externalId: Long? = null

    /**
     * Partner-flagged premium item (NauSys `highlight`). Rendered with the
     * yellow row treatment in the partner UI — Generator, Electric winch,
     * outboard engine, Electric toilet are typical highlights. MMK doesn't
     * surface a highlight flag, so MMK rows always default to false.
     */
    @Column(name = "highlight", nullable = false)
    open var highlight: Boolean = false

    /**
     * NauSys per-item count, e.g. 4 x Electric toilet. Null for MMK (no
     * equivalent field) and for NauSys rows where the partner sent only
     * the implicit single unit.
     */
    @Column(name = "quantity")
    open var quantity: java.math.BigDecimal? = null

    /**
     * Free-text qualifier — NauSys ships this as `comment.textEN` (e.g.
     * "Honda 20hp", "Air conditioning in all cabins and lounge"); MMK ships
     * the same idea in `EquipmentItemRaw.value` ("130 L", "7,6 kw", "only
     * in cabins"). Sync writes whichever the partner provides into this
     * column; renderers display it as a sub-line under the equipment name.
     */
    @Column(name = "comment", length = Integer.MAX_VALUE)
    open var comment: String? = null
}
