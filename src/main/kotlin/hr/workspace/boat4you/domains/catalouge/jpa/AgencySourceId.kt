package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.validation.constraints.NotNull
import org.hibernate.Hibernate
import java.io.Serializable
import java.util.Objects

@Embeddable
open class AgencySourceId : Serializable {
    @NotNull
    @Column(name = "agency_id", nullable = false)
    open var agencyId: Long? = null

    @NotNull
    @Column(name = "external_system_id", nullable = false)
    open var externalSystemId: Int? = null

    override fun hashCode(): Int = Objects.hash(agencyId, externalSystemId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false

        other as AgencySourceId

        return agencyId == other.agencyId &&
            externalSystemId == other.externalSystemId
    }

    companion object {
        private const val serialVersionUID = -4250801574888596220L
    }
}
