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
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal

@Entity
@Table(name = "custom_yacht_details")
open class CustomYachtDetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    open var id: Long? = null

    @NotNull
    @Column(name = "low_price", nullable = false)
    open var lowPrice: BigDecimal? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "yacht_id", nullable = false)
    open var yacht: Yacht? = null

    @Size(max = 500)
    @Column(name = "video_url", length = 500)
    open var videoUrl: String? = null

    /**
     * Saved on our services
     */
    @Size(max = 500)
    @Column(name = "pdf_url", length = 500)
    open var pdfUrl: String? = null

    /**
     * Id of a country in a format c-{country.id}
     */
    @NotNull
    @Column(name = "country_key", nullable = false, length = Integer.MAX_VALUE)
    open var countryKey: String? = null

    @Size(max = 2500)
    @Column(name = "price_description", length = 2500)
    open var priceDescription: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id")
    open var country: Country? = null

    /**
     * Free-text "Saloon and Cabins" block — admin pastes a multi-line list
     * from the boat owner. Each non-empty line renders as a checkmark item
     * in the public Amenities tab. Replaces the equipment dropdown for
     * custom yachts because the predefined catalog can't capture
     * one-off entries like specific bed sizes or named appliance brands.
     */
    @Column(name = "amenities_text", columnDefinition = "TEXT")
    open var amenitiesText: String? = null

    /**
     * Free-text "Entertainment" block — toys, water sports gear, board
     * games, etc. Same multi-line format as amenitiesText.
     */
    @Column(name = "toys_text", columnDefinition = "TEXT")
    open var toysText: String? = null

    /**
     * Descriptive engine string admin types verbatim ("2x Volvo IPS 1050",
     * "560 HP MAN"). Replaces the kW-only Yacht.enginePower for custom
     * yachts where owners mix units, brands and twin-engine notation.
     * Yacht.engine_power stays NULL for new custom listings so the
     * numeric engine-power range filter on /search skips them — Mario
     * doesn't want custom yachts to compete in that filter at all.
     */
    @Column(name = "engine_text", columnDefinition = "TEXT")
    open var engineText: String? = null
}
