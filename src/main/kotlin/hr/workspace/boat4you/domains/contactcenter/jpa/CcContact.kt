package hr.workspace.boat4you.domains.contactcenter.jpa

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

/** A person the contact center knows, from a chat or a form: one row however many visitors (browsers) they use. */
@Entity
@Table(name = "cc_contact")
open class CcContact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "name", length = 120)
    open var name: String? = null

    @Column(name = "email", length = 254)
    open var email: String? = null

    @Column(name = "phone", length = 40)
    open var phone: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: ContactStatus = ContactStatus.NEW

    @Column(name = "note")
    open var note: String? = null

    @Column(name = "email_verified", nullable = false)
    open var emailVerified: Boolean = false

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()

    @ElementCollection
    @CollectionTable(name = "cc_contact_tag", joinColumns = [JoinColumn(name = "contact_id")])
    @Column(name = "tag", length = 40)
    open var tags: MutableSet<String> = mutableSetOf()

    /** Retention deletes a contact 24 months after `updated_at` (ContactCenterRetention), so every update of the row must move it. */
    @PreUpdate
    fun touchUpdatedAt() {
        updatedAt = Instant.now()
    }
}
