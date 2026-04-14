package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Entity
@Table(name = "extras")
open class Extra {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Size(max = 100)
    @NotNull
    @Column(name = "label_code", nullable = false, length = 100)
    open var labelCode: String? = null

    @Size(max = 100)
    @Column(name = "name", length = 100)
    open var name: String? = null

    /**
     * List of keys to match mmk and nausys extras
     */
    @NotNull
    @Column(name = "match_keys", nullable = false, length = Integer.MAX_VALUE)
    open var matchKeys: String? = null

    /**
     * If its returned for UI filters and position in order.
     */
    @Column(name = "filter_order")
    open var filterOrder: Short? = null

    fun getMatchKeysList(): Set<String> {
        return matchKeys?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
    }
}
