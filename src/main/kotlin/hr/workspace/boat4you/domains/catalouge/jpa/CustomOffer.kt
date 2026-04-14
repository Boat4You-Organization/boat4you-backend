package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.users.jpa.UserEntity
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
import jakarta.validation.constraints.Size
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * Custom offer can be created based on user inquiry over the system, over phone call, email, etc.
 */
@Entity
@Table(
    name = "custom_offer",
)
open class CustomOffer {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @Column(name = "created_at", nullable = false)
    open var createdAt: LocalDateTime? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "inquiry_id")
    open var inquiry: Inquiry? = null

    /**
     * 6 chars should be enugh for 56.8 billion combinations
     */
    @Size(max = 6)
    @NotNull
    @Column(name = "short_url", nullable = false, length = 6)
    open var shortUrl: String? = null

    /**
     * This is query string for yacht search
     */
    @Size(max = 1000)
    @NotNull
    @Column(name = "long_url", nullable = false, length = 1000)
    open var longUrl: String? = null

    /**
     * Original parameters that triggered creation of this offer
     */
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request", nullable = false)
    open var request: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "user_id")
    open var user: UserEntity? = null

    @Size(max = 255)
    @Column(name = "email")
    open var email: String? = null
}
