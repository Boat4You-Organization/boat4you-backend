package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "inquiry",
)
open class Inquiry {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "yacht_id")
    open var yacht: Yacht? = null

    @NotNull
    @Column(name = "created_at", nullable = false)
    open var createdAt: LocalDateTime? = null

    @Column(name = "date_from")
    open var dateFrom: LocalDate? = null

    @Column(name = "date_to")
    open var dateTo: LocalDate? = null

    @Size(max = 255)
    @Column(name = "name")
    open var name: String? = null

    @Size(max = 255)
    @Column(name = "surname")
    open var surname: String? = null

    @Size(max = 255)
    @NotNull
    @Column(name = "email", nullable = false)
    open var email: String? = null

    @Size(max = 63)
    @NotNull
    @Column(name = "phone", length = 63, nullable = false)
    open var phone: String? = null

    @Size(max = 2000)
    @Column(name = "message", length = 2000)
    open var message: String? = null

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "status", nullable = false)
    open var status: InquiryStatus? = null

    // AI call center (V9_112, design infra/docs/ai-call-center-dizajn-2026-10-27.md §6.3)

    /** Key of the site the inquiry came from (CallSite.key), from the form's explicit `site` field. */
    @Column(name = "site", length = 40)
    open var site: String? = null

    @Column(name = "locale", length = 5)
    open var locale: String? = null

    /** The mandatory consent checkbox (calls, AI, recording). No consent = never called. */
    @Column(name = "contact_consent", nullable = false)
    open var contactConsent: Boolean = false

    @Column(name = "consent_text_version", length = 20)
    open var consentTextVersion: String? = null

    @Column(name = "consent_at")
    open var consentAt: Instant? = null

    /** The phone in E.164; null = not readable as an international number = never called. */
    @Column(name = "phone_e164", length = 20)
    open var phoneE164: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    open var details: Map<String, Any?>? = null

    @Column(name = "do_not_call", nullable = false)
    open var doNotCall: Boolean = false

    @Column(name = "offer_sent_at")
    open var offerSentAt: Instant? = null

    @Column(name = "offer_note", length = 300)
    open var offerNote: String? = null

    /** Mario's "Nazvat ću sam": no more AI calls for this inquiry. */
    @Column(name = "human_takeover_at")
    open var humanTakeoverAt: Instant? = null
}
