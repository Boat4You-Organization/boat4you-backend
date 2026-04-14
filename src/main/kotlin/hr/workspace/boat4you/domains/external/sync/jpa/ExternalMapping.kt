package hr.workspace.boat4you.domains.external.sync.jpa

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

/**
 * Used for mapping of external system id to boat4you id
 */
@Entity
@Table(
    name = "external_mapping",
    uniqueConstraints = [
        UniqueConstraint(
            name = "external_system_uk1",
            columnNames = ["external_id", "external_system_id", "system_id", "type"],
        ),
    ],
)
open class ExternalMapping {
    companion object {
        const val YACHT_AGENCY_EXTERNAL_MAPPING_KEY = "Yacht-AgencyId-"
        const val RESERVATION_YACHT_EXTERNAL_MAPPING_KEY = "Reservation-YachtId-"
    }

    constructor(externalId: Long?, systemId: Long?, type: String?, externalSystem: ExternalSystem?, extendedType: String?) {
        this.externalId = externalId
        this.systemId = systemId
        this.type = type
        this.externalSystem = externalSystem
        this.extendedType = extendedType
    }

    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @Column(name = "external_id", nullable = false)
    open var externalId: Long? = null

    @NotNull
    @Column(name = "system_id", nullable = false)
    open var systemId: Long? = null

    /**
     * Name of destination table for mapping or custom calculated key
     */
    @Size(max = 100)
    @NotNull
    @Column(name = "type", nullable = false, length = 100)
    open var type: String? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "external_system_id", nullable = false)
    open var externalSystem: ExternalSystem? = null

    @Size(max = 100)
    @Column(name = "extended_type", length = 100)
    open var extendedType: String? = null
}
