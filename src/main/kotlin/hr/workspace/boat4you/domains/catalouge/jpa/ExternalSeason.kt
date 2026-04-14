package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * ExternalSeason is currently used only for NauSYS sync.
 */
@Entity
@Table(name = "external_seasons")
open class ExternalSeason {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "valid_from")
    open var validFrom: LocalDate? = null

    @Column(name = "valid_to")
    open var validTo: LocalDate? = null

    @Column(name = "external_id")
    open var externalId: Long? = null

    @Size(max = 255)
    @Column(name = "name")
    open var name: String? = null

    @Column(name = "default_season")
    open var defaultSeason: Boolean? = null
}
