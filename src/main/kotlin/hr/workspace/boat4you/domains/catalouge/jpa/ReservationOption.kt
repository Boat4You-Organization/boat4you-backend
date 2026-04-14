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
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.LocalDate

/**
 * Reservation options like shorter than 7 day reservation, checkin days, etc
 */
@Entity
@Table(
    name = "reservation_options",
)
open class ReservationOption {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @Column(name = "date_from", nullable = false)
    open var dateFrom: LocalDate? = null

    @Column(name = "date_to")
    open var dateTo: LocalDate? = null

    @NotNull
    @Column(name = "minimal_duration", nullable = false)
    open var minimalDuration: Short? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "yacht_id", nullable = false)
    open var yacht: Yacht? = null

    @NotNull
    @Column(name = "checkin_mon", nullable = false)
    open var checkinMon: Boolean? = false

    @NotNull
    @Column(name = "checkin_tue", nullable = false)
    open var checkinTue: Boolean? = false

    @NotNull
    @Column(name = "checkin_wed", nullable = false)
    open var checkinWed: Boolean? = false

    @NotNull
    @Column(name = "checkin_thu", nullable = false)
    open var checkinThu: Boolean? = false

    @NotNull
    @Column(name = "checkin_fri", nullable = false)
    open var checkinFri: Boolean? = false

    @NotNull
    @Column(name = "checkin_sat", nullable = false)
    open var checkinSat: Boolean? = false

    @NotNull
    @Column(name = "checkin_sun", nullable = false)
    open var checkinSun: Boolean? = false

    @NotNull
    @Column(name = "checkout_mon", nullable = false)
    open var checkoutMon: Boolean? = false

    @NotNull
    @Column(name = "checkout_tue", nullable = false)
    open var checkoutTue: Boolean? = false

    @NotNull
    @Column(name = "checkout_wed", nullable = false)
    open var checkoutWed: Boolean? = false

    @NotNull
    @Column(name = "checkout_thu", nullable = false)
    open var checkoutThu: Boolean? = false

    @NotNull
    @Column(name = "checkout_fri", nullable = false)
    open var checkoutFri: Boolean? = false

    @NotNull
    @Column(name = "checkout_sat", nullable = false)
    open var checkoutSat: Boolean? = false

    @NotNull
    @Column(name = "checkout_sun", nullable = false)
    open var checkoutSun: Boolean? = false
}
