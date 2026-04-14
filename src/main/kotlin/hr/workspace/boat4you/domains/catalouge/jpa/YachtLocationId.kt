package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.validation.constraints.NotNull
import org.hibernate.Hibernate
import java.io.Serializable
import java.util.Objects

@Embeddable
open class YachtLocationId : Serializable {
    @NotNull
    @Column(name = "location_id", nullable = false)
    open var locationId: Long? = null

    @NotNull
    @Column(name = "yacht_id", nullable = false)
    open var yachtId: Long? = null

    override fun hashCode(): Int = Objects.hash(locationId, yachtId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false

        other as YachtLocationId

        return locationId == other.locationId &&
            yachtId == other.yachtId
    }

    companion object {
        private const val serialVersionUID = 3545820696320641644L
    }
}
