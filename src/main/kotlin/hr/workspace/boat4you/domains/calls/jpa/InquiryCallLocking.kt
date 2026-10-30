package hr.workspace.boat4you.domains.calls.jpa

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.transaction.annotation.Transactional

/** Row locking for [InquiryCallRepository] (a Spring Data fragment). */
interface InquiryCallLocking {
    /**
     * The call re-read from the database and locked (FOR UPDATE) until the caller's transaction ends, or null when it is gone.
     * Every change that can race another (webhooks, reconcile, the dialler) starts here, so a full-row save never writes back
     * columns another transaction changed meanwhile. Unflushed changes to the same entity are discarded. Joins the caller's
     * transaction (the lock lasts until it ends); without one it runs in its own, which only tests do.
     */
    fun lockFresh(id: Long): InquiryCall?
}

/** Open, so Spring can wrap it in its transaction. */
open class InquiryCallLockingImpl(
    private val em: EntityManager,
) : InquiryCallLocking {
    @Transactional
    override fun lockFresh(id: Long): InquiryCall? = em.find(InquiryCall::class.java, id)?.also { em.refresh(it, LockModeType.PESSIMISTIC_WRITE) }
}
