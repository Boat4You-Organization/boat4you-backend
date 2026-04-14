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
@Table(name = "country")
open class Country {
    @Id
    @Column(name = "id", columnDefinition = "SERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Int? = null

    @Size(max = 100)
    @Column(name = "name", length = 100)
    open var name: String? = null

    @Size(max = 2)
    @NotNull
    @Column(name = "code2", nullable = false, length = 2)
    open var code2: String? = null

    @Size(max = 3)
    @NotNull
    @Column(name = "code3", nullable = false, length = 3)
    open var code3: String? = null

    @Size(max = 15)
    @NotNull
    @Column(name = "continent", nullable = false, length = 15)
    open var continent: String? = null
}
