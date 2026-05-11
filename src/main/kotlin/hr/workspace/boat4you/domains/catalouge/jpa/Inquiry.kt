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
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
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
}
