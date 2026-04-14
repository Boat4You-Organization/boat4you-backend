package hr.workspace.boat4you.domains.roles.jpa

import hr.workspace.boat4you.common.jpa.AbstractEntity
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import jakarta.persistence.Entity
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.envers.Audited
import org.hibernate.envers.RelationTargetAuditMode

@Entity
@Table(
    name = "role_assignments",
    uniqueConstraints = [
        UniqueConstraint(name = "uidx_role_assignments_user_role", columnNames = ["user_id", "role_id"]),
    ],
)
@Audited
class RoleAssignmentEntity : AbstractEntity<Long>() {
    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id", columnDefinition = "BIGINT", nullable = false)
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    lateinit var user: UserEntity

    @ManyToOne
    @JoinColumn(name = "role_id", referencedColumnName = "id", columnDefinition = "BIGINT", nullable = false)
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    lateinit var role: RoleEntity

    fun isTechnician(): Boolean = role.isTechnician

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RoleAssignmentEntity

        if (user.id != other.user.id) return false
        if (role.id != other.role.id) return false

        return true
    }

    override fun hashCode(): Int {
        var result = user.hashCode()
        result = 31 * result + role.hashCode()
        return result
    }
}
