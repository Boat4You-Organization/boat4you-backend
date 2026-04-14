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
import jakarta.validation.constraints.Size
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

@Entity
@Table(
    name = "yacht_image",
)
open class YachtImage {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Size(max = 255)
    @Column(name = "url")
    open var url: String? = null

    @Size(max = 255)
    @Column(name = "external_url")
    open var externalUrl: String? = null

    /**
     * Position in images array displayed on FE
     */
    @Column(name = "\"position\"")
    open var position: Short? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "yacht_id", nullable = false)
    open var yacht: Yacht? = null

    /**
     * From Nausys only.
     */
    @Column(name = "main_image")
    open var mainImage: Boolean? = null

    @ColumnDefault("false")
    @Column(name = "synced")
    open var synced: Boolean? = null
}
