package hr.workspace.boat4you.domains.roles.jpa

import hr.workspace.boat4you.common.jpa.AbstractEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.Formula

@Entity
@Table(name = "roles")
class RoleEntity : AbstractEntity<Long>() {
    @Column(name = "name", columnDefinition = "VARCHAR(255)", nullable = false, unique = true)
    lateinit var name: String

    @OneToMany(mappedBy = "role")
    var roleAssignments: MutableSet<RoleAssignmentEntity> = mutableSetOf()

    @Formula("name = 'TECHNICIAN'")
    var isTechnician: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RoleEntity

        return name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }
}
