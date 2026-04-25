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
import jakarta.validation.constraints.Size
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal

@Entity
@Table(
    name = "model",
)
open class Model {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Size(max = 255)
    @NotNull
    @Column(name = "name", nullable = false)
    open var name: String? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "manufacturer_id", nullable = false)
    open var manufacturer: Manufacturer? = null

    /**
     * Only for nausys model category (vessel type)
     */
    @Column(name = "external_category_id")
    open var externalCategoryId: Long? = null

    /**
     * Length overall in meters. Populated from Nausys RestYachtModel.loa (the
     * RestYacht per-yacht payload has no length field). Yacht sync falls back
     * to this when the per-yacht value is null so spec cards always show a
     * length for Nausys-sourced yachts.
     */
    @Column(name = "length")
    open var length: BigDecimal? = null

    /** Beam in meters — same story as length. */
    @Column(name = "beam")
    open var beam: BigDecimal? = null
}
