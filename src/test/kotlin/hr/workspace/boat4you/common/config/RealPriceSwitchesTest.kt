package hr.workspace.boat4you.common.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import hr.workspace.boat4you.common.config.RealPriceSwitches.MmkOptionVerifyMode
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysD3Mode
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysReverifyMode
import hr.workspace.boat4you.common.config.RealPriceSwitches.OnOff
import hr.workspace.boat4you.common.config.RealPriceSwitches.PartnerPriceGuardMode
import hr.workspace.boat4you.common.config.RealPriceSwitches.SearchPredicate
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.settings.dto.SettingsDto
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.jpa.SettingsRepository
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration

class RealPriceSwitchesTest {
    /** The `settings` table as `SELECT name, value FROM settings ORDER BY id` returns it. */
    private class FakeSettings : JdbcTemplate() {
        val rows = mutableListOf<Pair<String, String?>>()
        var reads = 0
        var fail = false

        override fun queryForList(sql: String): MutableList<MutableMap<String, Any?>> {
            reads++
            if (fail) throw DataAccessResourceFailureException("database down")
            return rows.map { (name, value) -> mutableMapOf<String, Any?>("name" to name, "value" to value) }.toMutableList()
        }

        fun set(
            key: SettingsKeyEnum,
            value: String?,
        ) {
            rows.removeIf { it.first == key.name }
            rows += key.name to value
        }
    }

    private val db = FakeSettings()
    private val caches = ConcurrentMapCacheManager("yachtSearchListCache", "relaxSuggestCache", "facetDistributionCache", "countriesCache")
    private var now = 1_000_000_000L
    private val switches = RealPriceSwitches(db, caches, Duration.ofSeconds(60)).also { s -> s.ticker = { now } }
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(RealPriceSwitches::class.java) as Logger

    @BeforeEach
    fun attachLog() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachLog() {
        logger.detachAppender(appender)
    }

    private fun advance(d: Duration) {
        now += d.toNanos()
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    @Test
    fun `no rows - every switch is its default (today's behaviour)`() {
        switches.snapshot() shouldBe RealPriceSwitches.Snapshot()
        switches.searchPredicate shouldBe SearchPredicate.LEGACY
        switches.searchPriceFloor shouldBe OnOff.OFF
        switches.demoteStaleOptions shouldBe true
        switches.bookingC1 shouldBe OnOff.OFF
        switches.partnerPriceGuard shouldBe PartnerPriceGuardMode.OFF
        switches.extrasZeroOptionalOnSite shouldBe OnOff.OFF
        switches.mmkOptionVerify shouldBe MmkOptionVerifyMode.OFF
        switches.mmkOnDemandStrike shouldBe OnOff.OFF
        switches.nausysReverify shouldBe NauSysReverifyMode.OFF
        switches.nausysD3Mode shouldBe NauSysD3Mode.LEGACY
        switches.nausysWarmLongRanges shouldBe OnOff.OFF
        switches.galleryExtended shouldBe OnOff.OFF
        db.reads shouldBe 1
    }

    @Test
    fun `valid rows are read, trimmed and case-insensitive, unrelated rows ignored`() {
        db.rows += "CARD_PAYMENT_SURCHARGE" to "5"
        db.rows += "SOME_FUTURE_KEY" to "X"
        db.set(SettingsKeyEnum.SEARCH_PREDICATE, " d1 ")
        db.set(SettingsKeyEnum.SEARCH_PRICE_FLOOR, "ON")
        db.set(SettingsKeyEnum.SEARCH_DEMOTE_STALE_OPTIONS, "FALSE")
        db.set(SettingsKeyEnum.BOOKING_C1, "on")
        db.set(SettingsKeyEnum.PARTNER_PRICE_GUARD, "ENFORCE")
        db.set(SettingsKeyEnum.EXTRAS_ZERO_OPTIONAL_ON_SITE, "ON")
        db.set(SettingsKeyEnum.MMK_OPTION_VERIFY, "LOG")
        db.set(SettingsKeyEnum.MMK_ONDEMAND_STRIKE, "ON")
        db.set(SettingsKeyEnum.NAUSYS_REVERIFY, "WRITE")
        db.set(SettingsKeyEnum.NAUSYS_D3_MODE, "LOG")
        db.set(SettingsKeyEnum.NAUSYS_WARM_LONG_RANGES, "ON")
        db.set(SettingsKeyEnum.GALLERY_EXTENDED, "on")

        switches.snapshot() shouldBe
            RealPriceSwitches.Snapshot(
                searchPredicate = SearchPredicate.D1,
                searchPriceFloor = OnOff.ON,
                demoteStaleOptions = false,
                bookingC1 = OnOff.ON,
                partnerPriceGuard = PartnerPriceGuardMode.ENFORCE,
                extrasZeroOptionalOnSite = OnOff.ON,
                mmkOptionVerify = MmkOptionVerifyMode.LOG,
                mmkOnDemandStrike = OnOff.ON,
                nausysReverify = NauSysReverifyMode.WRITE,
                nausysD3Mode = NauSysD3Mode.LOG,
                nausysWarmLongRanges = OnOff.ON,
                galleryExtended = OnOff.ON,
            )
        switches.bookingC1.isOn shouldBe true
        switches.galleryExtended.isOn shouldBe true
        warnings() shouldBe emptyList()
    }

    @Test
    fun `invalid or null value - default, the other switches unaffected`() {
        db.set(SettingsKeyEnum.SEARCH_PREDICATE, "D2")
        db.set(SettingsKeyEnum.SEARCH_DEMOTE_STALE_OPTIONS, "yes")
        db.set(SettingsKeyEnum.PARTNER_PRICE_GUARD, null)
        db.set(SettingsKeyEnum.NAUSYS_REVERIFY, "LOG")

        switches.searchPredicate shouldBe SearchPredicate.LEGACY
        switches.demoteStaleOptions shouldBe true
        switches.partnerPriceGuard shouldBe PartnerPriceGuardMode.OFF
        switches.nausysReverify shouldBe NauSysReverifyMode.LOG
    }

    @Test
    fun `newest row wins when a key has two rows`() {
        db.rows += "BOOKING_C1" to "OFF"
        db.rows += "BOOKING_C1" to "ON"
        switches.bookingC1 shouldBe OnOff.ON
    }

    @Test
    fun `snapshot is cached for the TTL and reloaded after it`() {
        db.set(SettingsKeyEnum.BOOKING_C1, "ON")
        switches.bookingC1 shouldBe OnOff.ON
        db.set(SettingsKeyEnum.BOOKING_C1, "OFF")

        advance(Duration.ofSeconds(59))
        switches.bookingC1 shouldBe OnOff.ON
        switches.poll()
        db.reads shouldBe 1

        advance(Duration.ofSeconds(1))
        switches.bookingC1 shouldBe OnOff.OFF
        db.reads shouldBe 2
    }

    @Test
    fun `poll refreshes a stale snapshot without any caller`() {
        switches.snapshot()
        db.set(SettingsKeyEnum.SEARCH_PREDICATE, "D1")
        advance(Duration.ofSeconds(61))
        switches.poll()
        db.reads shouldBe 2
        switches.searchPredicate shouldBe SearchPredicate.D1
        db.reads shouldBe 2
    }

    @Test
    fun `a change of a SEARCH switch clears the search caches, other caches stay`() {
        switches.snapshot()
        listOf("yachtSearchListCache", "relaxSuggestCache", "facetDistributionCache", "countriesCache")
            .forEach { caches.getCache(it)!!.put("k", "v") }

        db.set(SettingsKeyEnum.SEARCH_PRICE_FLOOR, "ON")
        advance(Duration.ofSeconds(60))
        switches.poll()

        RealPriceSwitches.SEARCH_CACHES.forEach { caches.getCache(it)!!.get("k") shouldBe null }
        caches.getCache("countriesCache")!!.get("k")!!.get() shouldBe "v"
        switches.searchCacheKey() shouldBe "rp:LEGACY:ON:true"
    }

    @Test
    fun `each SEARCH switch triggers the clear, a non-search switch does not`() {
        switches.snapshot()
        val cache = caches.getCache("yachtSearchListCache")!!
        for ((key, value) in listOf(
            SettingsKeyEnum.SEARCH_PREDICATE to "D1",
            SettingsKeyEnum.SEARCH_PRICE_FLOOR to "ON",
            SettingsKeyEnum.SEARCH_DEMOTE_STALE_OPTIONS to "false",
        )) {
            cache.put("k", "v")
            db.set(key, value)
            advance(Duration.ofSeconds(60))
            switches.poll()
            cache.get("k") shouldBe null
        }

        cache.put("k", "v")
        db.set(SettingsKeyEnum.BOOKING_C1, "ON")
        db.set(SettingsKeyEnum.NAUSYS_REVERIFY, "WRITE")
        db.set(SettingsKeyEnum.GALLERY_EXTENDED, "ON")
        advance(Duration.ofSeconds(60))
        switches.poll()
        switches.bookingC1 shouldBe OnOff.ON
        switches.galleryExtended shouldBe OnOff.ON
        cache.get("k")!!.get() shouldBe "v"
    }

    @Test
    fun `the first load never clears caches`() {
        db.set(SettingsKeyEnum.SEARCH_PREDICATE, "D1")
        caches.getCache("yachtSearchListCache")!!.put("k", "v")
        switches.snapshot()
        caches.getCache("yachtSearchListCache")!!.get("k")!!.get() shouldBe "v"
    }

    @Test
    fun `a failed load keeps the previous snapshot`() {
        db.set(SettingsKeyEnum.SEARCH_PREDICATE, "D1")
        switches.searchPredicate shouldBe SearchPredicate.D1
        db.fail = true
        advance(Duration.ofSeconds(60))
        switches.searchPredicate shouldBe SearchPredicate.D1
        warnings().count { it.contains("settings read failed") } shouldBe 1
    }

    @Test
    fun `a failed first load gives the defaults`() {
        db.fail = true
        switches.snapshot() shouldBe RealPriceSwitches.Snapshot()
    }

    @Test
    fun `an invalid value warns at most once per key per hour, a missing row never warns`() {
        db.set(SettingsKeyEnum.SEARCH_PREDICATE, "bogus")
        switches.snapshot()
        warnings().size shouldBe 1 // the 11 missing rows are the documented default: silent
        warnings().count { it.contains("SEARCH_PREDICATE") && it.contains("bogus") } shouldBe 1

        repeat(5) {
            advance(Duration.ofMinutes(10))
            switches.snapshot()
        }
        warnings().size shouldBe 1

        advance(Duration.ofMinutes(10))
        switches.snapshot()
        warnings().size shouldBe 2
    }

    @Test
    fun `default state logs one neutral INFO line and no WARN, a change logs again`() {
        repeat(3) {
            switches.snapshot()
            advance(Duration.ofMinutes(10))
        }
        warnings() shouldBe emptyList()
        val infos = { appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage } }
        infos() shouldBe listOf("Real-price switches effective: 0 of 13 away from the default, search rp:LEGACY:OFF:true")
        infos().none { it.contains("mmk", ignoreCase = true) || it.contains("nausys", ignoreCase = true) } shouldBe true

        db.set(SettingsKeyEnum.MMK_OPTION_VERIFY, "LOG")
        advance(Duration.ofSeconds(60))
        switches.snapshot()
        infos().last() shouldBe "Real-price switches effective: 1 of 13 away from the default, search rp:LEGACY:OFF:true"
        infos().none { it.contains("mmk", ignoreCase = true) || it.contains("nausys", ignoreCase = true) } shouldBe true
    }

    // AdminSettingsService.validateSetting for the switch keys

    private val adminSettings = AdminSettingsService(Mockito.mock(SettingsRepository::class.java))

    private val expectedAllowed =
        mapOf(
            SettingsKeyEnum.SEARCH_PREDICATE to listOf("LEGACY", "D1"),
            SettingsKeyEnum.SEARCH_PRICE_FLOOR to listOf("OFF", "ON"),
            SettingsKeyEnum.SEARCH_DEMOTE_STALE_OPTIONS to listOf("true", "false"),
            SettingsKeyEnum.BOOKING_C1 to listOf("OFF", "ON"),
            SettingsKeyEnum.PARTNER_PRICE_GUARD to listOf("OFF", "ALERT", "ENFORCE"),
            SettingsKeyEnum.EXTRAS_ZERO_OPTIONAL_ON_SITE to listOf("OFF", "ON"),
            SettingsKeyEnum.MMK_OPTION_VERIFY to listOf("OFF", "LOG", "ON"),
            SettingsKeyEnum.MMK_ONDEMAND_STRIKE to listOf("OFF", "ON"),
            SettingsKeyEnum.NAUSYS_REVERIFY to listOf("OFF", "LOG", "WRITE"),
            SettingsKeyEnum.NAUSYS_D3_MODE to listOf("LEGACY", "LOG", "WRITE"),
            SettingsKeyEnum.NAUSYS_WARM_LONG_RANGES to listOf("OFF", "ON"),
            SettingsKeyEnum.REVERIFY_DIGEST to listOf("ON", "OFF"),
            SettingsKeyEnum.GALLERY_EXTENDED to listOf("OFF", "ON"),
        )

    @Test
    fun `allowed values per key match the design, default first`() {
        RealPriceSwitches.ALLOWED_VALUES shouldBe expectedAllowed
    }

    @Test
    fun `validateSetting accepts every allowed value of every switch key`() {
        expectedAllowed.forEach { (key, values) ->
            values.forEach { v ->
                adminSettings.validateSetting(SettingsDto(key, v))
                adminSettings.validateSetting(SettingsDto(key, v.lowercase()))
            }
        }
    }

    @Test
    fun `validateSetting rejects other values of every switch key`() {
        expectedAllowed.keys.forEach { key ->
            listOf(null, "", "BOGUS", "1", "D2", "maybe").forEach { v ->
                val e = shouldThrow<ParameterValidationException> { adminSettings.validateSetting(SettingsDto(key, v)) }
                e.badOrMissingParameters["value"] shouldBe "Value must be one of: ${expectedAllowed.getValue(key).joinToString(", ")}"
            }
        }
        // a value of another switch is not valid here
        shouldThrow<ParameterValidationException> {
            adminSettings.validateSetting(SettingsDto(SettingsKeyEnum.SEARCH_PREDICATE, "ON"))
        }
        shouldThrow<ParameterValidationException> {
            adminSettings.validateSetting(SettingsDto(SettingsKeyEnum.NAUSYS_REVERIFY, "ON"))
        }
    }

    @Test
    fun `existing settings keep their validation`() {
        adminSettings.validateSetting(SettingsDto(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE, "5"))
        shouldThrow<ParameterValidationException> {
            adminSettings.validateSetting(SettingsDto(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE, "ON"))
        }
    }
}
