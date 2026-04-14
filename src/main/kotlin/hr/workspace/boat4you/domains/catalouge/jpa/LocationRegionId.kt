package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.validation.constraints.NotNull
import org.hibernate.Hibernate
import java.io.Serializable
import java.util.Objects

@Embeddable
open class LocationRegionId : Serializable {
    @NotNull
    @Column(name = "region_id", nullable = false)
    open var regionId: Int? = null

    @NotNull
    @Column(name = "location_id", nullable = false)
    open var locationId: Long? = null

    override fun hashCode(): Int = Objects.hash(regionId, locationId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false

        other as LocationRegionId

        return regionId == other.regionId &&
            locationId == other.locationId
    }

    companion object {
        private const val serialVersionUID = 8887337769139571675L
    }
}
