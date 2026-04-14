package hr.workspace.boat4you.common.jpa

import org.hibernate.envers.RevisionListener

class CustomRevisionListener : RevisionListener {
    override fun newRevision(genericRevisionEntity: Any) {
        // TODO Fill when Security has been implemented

        /*val currentUserId = getAuthenticatedUserId()
        if (currentUserId != ANONYMOUS_USER_ID) {
            val revisionEntity = genericRevisionEntity as CustomRevisionEntity
            revisionEntity.setModifierUserId(currentUserId)
        }*/

        val revisionEntity = genericRevisionEntity as CustomRevisionEntity
        revisionEntity.setModifierUserId(1L)
    }
}
