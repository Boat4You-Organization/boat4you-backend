package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

@Entity
@Table(
    name = "agency_source",
)
open class AgencySource {
    @EmbeddedId
    open var id: AgencySourceId? = null

    @MapsId("agencyId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "agency_id", nullable = false)
    open var agency: Agency? = null

    @MapsId("externalSystemId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "external_system_id", nullable = false)
    open var externalSystem: ExternalSystem? = null

    @NotNull
    @Column(name = "\"primary\"", nullable = false)
    open var primary: Boolean? = false

    @NotNull
    @Column(name = "external_id", nullable = false)
    open var externalId: Long? = null
}
