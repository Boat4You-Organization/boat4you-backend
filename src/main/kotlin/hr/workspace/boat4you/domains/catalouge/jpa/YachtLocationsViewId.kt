package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import org.hibernate.Hibernate
import java.io.Serializable
import java.util.Objects

@Embeddable
open class YachtLocationsViewId : Serializable {
    @Column(name = "location_id")
    open var locationId: Long? = null
        protected set

    @Column(name = "yacht_id")
    open var yachtId: Long? = null
        protected set

    override fun hashCode(): Int = Objects.hash(locationId, yachtId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false

        other as YachtLocationsViewId

        return locationId == other.locationId &&
            yachtId == other.yachtId
    }

    companion object {
        private const val serialVersionUID = 0L
    }
}
