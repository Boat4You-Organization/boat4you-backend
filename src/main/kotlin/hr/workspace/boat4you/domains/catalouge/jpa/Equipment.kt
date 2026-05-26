package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Entity
@Table(name = "equipment")
open class Equipment {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Size(max = 100)
    @NotNull
    @Column(name = "name", nullable = false, length = 100)
    open var name: String? = null

    @Size(max = 100)
    @NotNull
    @Column(name = "label_code", nullable = false, length = 100)
    open var labelCode: String? = null

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    open var category: CategoryEnum? = null

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
