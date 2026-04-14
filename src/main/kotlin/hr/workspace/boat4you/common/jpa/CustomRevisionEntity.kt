package hr.workspace.boat4you.common.jpa

import jakarta.persistence.Entity
import org.hibernate.envers.DefaultRevisionEntity
import org.hibernate.envers.RevisionEntity

@Entity(name = "revinfo")
@RevisionEntity(CustomRevisionListener::class)
class CustomRevisionEntity : DefaultRevisionEntity() {
    private var modifierUserId: Long? = null

    fun setModifierUserId(userId: Long) {
        modifierUserId = userId
    }
}
