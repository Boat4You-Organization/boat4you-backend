package hr.workspace.boat4you.domains.external.sync.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "service_call")
open class ServiceCall {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Size(max = 255)
    @Column(name = "route")
    open var route: String? = null

    @Column(name = "request_body")
    open var requestBody: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    open var responseBody: String? = null

    @Column(name = "response_status")
    open var responseStatus: String? = null

    @NotNull
    @Column(name = "external_system_id")
    open var externalSystemId: Int? = null

    /**
     * This will indicate whether the service call was successful or not.
     * WARNING: Nausys will return 200 even if the request was not successful, check response_status for details.
     */
    @Column(name = "success")
    open var success: Boolean? = null

    @Column(name = "received_at")
    open var receivedAt: Instant = Instant.now()
}
