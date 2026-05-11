package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(name = "offer_payment_plan")
open class OfferPaymentPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ColumnDefault("nextval('payment_plan_id_seq')")
    @Column(name = "id", nullable = false)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "offer_id")
    open var offer: Offer? = null

    @NotNull
    @Column(name = "date", nullable = false)
    open var date: LocalDate? = null

    @Column(name = "amount")
    open var amount: BigDecimal? = null

    @Column(name = "percentage")
    open var percentage: BigDecimal? = null

    // Id-based equals/hashCode. F2-026 — earlier the structural comparison
    // used mutable fields (offer, date, amount, percentage) as the equality
    // key, which broke `MutableSet<OfferPaymentPlan>` semantics on
    // Offer.offerPaymentPlans whenever any of those fields was reassigned
    // after Set insertion: the hashCode migrated buckets, leaving the
    // entity unreachable via `contains`/`remove`.
    //
    // Unpersisted entities (id == null) compare by reference identity —
    // two fresh-from-builder instances are never equal until at least one
    // is persisted. Standard JPA recommendation that avoids the
    // "two new entities accidentally collapse in a Set before flush" bug.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OfferPaymentPlan) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: javaClass.hashCode()
}
