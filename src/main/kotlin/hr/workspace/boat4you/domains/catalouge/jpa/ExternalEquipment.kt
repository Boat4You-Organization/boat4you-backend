package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
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
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

@Entity
@Table(name = "external_equipment")
open class ExternalEquipment {
    @Id
    @Column(name = "id", columnDefinition = "SERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Int? = null

    @NotNull
    @Column(name = "name", nullable = false, length = Integer.MAX_VALUE)
    open var name: String? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "external_system_id", nullable = false)
    open var externalSystem: ExternalSystem? = null

    @NotNull
    @Column(name = "external_id", nullable = false)
    open var externalId: Long? = null

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "type", nullable = false)
    open var type: ExternalEquipmentType? = null
}
