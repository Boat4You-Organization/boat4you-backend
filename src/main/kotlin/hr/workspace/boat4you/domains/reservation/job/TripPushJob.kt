package hr.workspace.boat4you.domains.reservation.job

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.TripPushService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

/**
 * Boat4You Trip scheduled pushes (phase 2, plan locked by Mario 4.7.2026):
 * T-7 "one week to go", T-1 "tomorrow's the day" + weather, day-of welcome +
 * weather, T+1 thank-you, and installment reminders 7 and 2 days before the
 * deadline. Installment pushes go ONLY to owner devices and NEVER carry
 * amounts — the crew shares the same trip hub. Runs daily at 09:40, right
 * after the 09:32 pre-charter email job; devices without a subscription
 * simply get nothing (email remains the fallback channel for everything).
 */
@Profile("data-sync")
@Component
class TripPushJob(
    private val reservationRepository: ReservationRepository,
    private val paymentPhaseRepository: ReservationPaymentPhaseRepository,
    private val tripPushService: TripPushService,
    private val objectMapper: ObjectMapper,
    @Value("\${application.trip-push.web-base-url}") private val webBaseUrl: String,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(WEATHER_TIMEOUT_SECONDS))
        .build()

    @Scheduled(cron = "0 40 9 ? * *")
    @SchedulerLock(name = "tripPushReminders", lockAtMostFor = "PT45M")
    @Transactional
    fun run() {
        if (!tripPushService.enabled) return
        val today = LocalDate.now()
        val weatherCache = mutableMapOf<String, String?>()
        var sent = 0

        sent += startingOn(today.plusDays(DAYS_WEEK_BEFORE)).sumOf { reservation ->
            push(
                reservation,
                tag = "t7",
                title = "One week to go ⛵",
                body = "${yachtLabel(reservation)} is waiting for you. Check your travel documents in the trip hub.",
            )
        }
        sent += startingOn(today.plusDays(1)).sumOf { reservation ->
            push(
                reservation,
                tag = "t1",
                title = "Tomorrow's the day!",
                body = listOfNotNull(
                    "Check-in tomorrow${marinaSuffix(reservation)}.",
                    weather(reservation, dayIndex = 1, cache = weatherCache),
                ).joinToString(" "),
            )
        }
        sent += startingOn(today).sumOf { reservation ->
            push(
                reservation,
                tag = "day0",
                title = "Welcome aboard ${yachtLabel(reservation)}!",
                body = listOfNotNull(
                    "Check-in today${marinaSuffix(reservation)}. Fair winds!",
                    weather(reservation, dayIndex = 0, cache = weatherCache),
                ).joinToString(" "),
            )
        }
        sent += endedOn(today.minusDays(1)).sumOf { reservation ->
            push(
                reservation,
                tag = "thanks",
                title = "Thank you for sailing with Boat4You 💙",
                body = "We hope ${yachtLabel(reservation)} treated you well. Your trip hub stays open for a while — see you on board again!",
            )
        }
        sent += installmentReminders(today)

        log.info("TripPushJob: delivered $sent push notification(s)")
    }

    private fun startingOn(date: LocalDate): List<Reservation> = reservationRepository.findConfirmedStartingBetween(
        status = ReservationStatus.RESERVATION,
        startTime = date.atStartOfDay(),
        endTime = date.plusDays(1).atStartOfDay(),
    )

    private fun endedOn(date: LocalDate): List<Reservation> = reservationRepository.findConfirmedEndingBetween(
        status = ReservationStatus.RESERVATION,
        startTime = date.atStartOfDay(),
        endTime = date.plusDays(1).atStartOfDay(),
    )

    /** Owner-only, amount-free by design — the crew opens the same hub. */
    private fun installmentReminders(today: LocalDate): Int =
        listOf(DAYS_WEEK_BEFORE, DAYS_LAST_CALL).sumOf { daysAhead ->
            paymentPhaseRepository.findPendingPayments(today.plusDays(daysAhead)).sumOf { phase ->
                val reservation = reservationRepository.findByReservationFlow(phase.reservationFlow)
                if (reservation == null || reservation.sysStatus == ReservationStatus.CANCELLED) {
                    0
                } else {
                    push(
                        reservation,
                        tag = "payment$daysAhead",
                        title = "Payment reminder",
                        body = "An installment for your booking ${reservation.reservationNumber ?: ""} is due in $daysAhead days.".trim(),
                        ownerOnly = true,
                    )
                }
            }
        }

    private fun push(reservation: Reservation, tag: String, title: String, body: String, ownerOnly: Boolean = false): Int {
        val id = reservation.id ?: return 0
        val token = reservation.tripToken ?: return 0
        return runCatching {
            tripPushService.sendToReservation(
                reservationId = id,
                title = title,
                body = body,
                url = "$webBaseUrl/trip/$token?push=$tag",
                tag = tag,
                ownerOnly = ownerOnly,
            )
        }.onFailure { log.error("Trip push '$tag' failed for reservation $id", it) }.getOrDefault(0)
    }

    private fun yachtLabel(reservation: Reservation): String =
        reservation.reservationFlow?.yacht?.name?.takeIf { it.isNotBlank() } ?: "your yacht"

    private fun marinaSuffix(reservation: Reservation): String =
        reservation.locationFrom?.name?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""

    /**
     * One-line Open-Meteo forecast for the marina ("Forecast: 29°C, wind up
     * to 12 kn."), null when the marina has no coordinates or the call fails
     * — the push simply goes out without weather.
     */
    private fun weather(reservation: Reservation, dayIndex: Int, cache: MutableMap<String, String?>): String? {
        val marina = reservation.locationFrom ?: return null
        val lat = marina.lat?.toDouble() ?: return null
        val lon = marina.lon?.toDouble() ?: return null
        return cache.getOrPut("$lat,$lon,$dayIndex") {
            runCatching {
                val uri = URI(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&daily=temperature_2m_max,wind_speed_10m_max&wind_speed_unit=kn&timezone=UTC&forecast_days=2",
                )
                val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(WEATHER_TIMEOUT_SECONDS)).GET().build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                require(response.statusCode() == 200) { "Open-Meteo HTTP ${response.statusCode()}" }
                val daily = objectMapper.readTree(response.body()).path("daily")
                val temp = daily.path("temperature_2m_max").path(dayIndex).takeIf { it.isNumber }?.let { BigDecimal(it.asText()) }
                val wind = daily.path("wind_speed_10m_max").path(dayIndex).takeIf { it.isNumber }?.let { BigDecimal(it.asText()) }
                if (temp == null || wind == null) {
                    null
                } else {
                    "Forecast: ${temp.toInt()}°C, wind up to ${wind.toInt()} kn."
                }
            }.onFailure { log.warn("Open-Meteo forecast failed for $lat,$lon", it) }.getOrNull()
        }
    }

    companion object {
        private const val DAYS_WEEK_BEFORE = 7L
        private const val DAYS_LAST_CALL = 2L
        private const val WEATHER_TIMEOUT_SECONDS = 4L
    }
}
