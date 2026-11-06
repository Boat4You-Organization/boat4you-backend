package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.domains.branding.BrandRegistry
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Mario 6.11.2026, Catamaran Greece: a visitor asked for "a Lagoon 46 or similar, 4 guest cabins, 19.-26.6.2027, out of
 * Lefkada or maybe Preveza". IRIS searched one marina (D-Marin Lefkas), never Preveza, had no model or size filter, offered
 * 6-cabin boats and never checked the boat the visitor was looking at, so it said no Lagoon 46 was free while Lagoon 46
 * Sundowner in Preveza Main Port was. A local HTTP server plays our public API with the live shapes of 6.11.2026.
 */
class AiChatToolAreaModelTest {
    private val mapper = ObjectMapper()
    private val seen = mutableListOf<String>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path
                val query = ex.requestURI.rawQuery ?: ""
                seen += path + "?" + query
                val q = params(query)
                val body =
                    when {
                        path.startsWith("/public/locations") ->
                            when (q["name"]?.single()) {
                                "Lefkas" -> """{"content":[$D_MARIN,$LEFKAS_PORT]}"""
                                "Preveza" -> """{"content":[$PREVEZA]}"""
                                "Alimos" -> """{"content":[$ALIMOS]}"""
                                "Ionian" -> """{"content":[{"id":"55","name":"Ionian Islands","locationType":"REGION","countryCode":"GR"},$D_MARIN]}"""
                                else -> """{"content":[]}"""
                            }
                        path == "/public/marinas" -> "[$D_MARIN,$LEFKAS_PORT,$PREVEZA,$ALIMOS]"
                        path == "/public/catalogue/manufacturers" ->
                            when (q["name"]?.single()) {
                                "Lagoon" -> """{"content":[{"id":1079,"name":"Lagoon"}]}"""
                                "Bali" -> """{"content":[{"id":1057,"name":"Bali Catamarans"}]}"""
                                else -> """{"content":[]}"""
                            }
                        path == "/public/catalogue/models" ->
                            when (q["manufacturerIds"]?.single() to q["name"]?.single()) {
                                "1079" to "46" ->
                                    """{"content":[{"id":4028,"name":"Lagoon 46"},{"id":2107,"name":"Lagoon 46 Fly"},{"id":999,"name":"Lagoon 460"}]}"""
                                "1057" to "4.2" -> """{"content":[{"id":3001,"name":"Bali 4.2"}]}"""
                                else -> """{"content":[]}"""
                            }
                        path.startsWith("/public/catalogue") -> "[]"
                        path == "/public/yachts/$SLUG" -> DETAIL
                        path == "/public/yachts/$SLUG/standard-offers" ->
                            """[{"dateFrom":"2027-07-10","dateTo":"2027-07-17","status":"FREE"},{"dateFrom":"2027-07-03","dateTo":"2027-07-10","status":"RESERVATION"}]"""
                        path == "/public/yachts/athens-boat-1" -> """{"id":1,"slug":"athens-boat-1","name":"X","location":{"countryCode":"HR"}}"""
                        path.startsWith("/public/yachts/") -> {
                            ex.sendResponseHeaders(404, -1)
                            return@createContext
                        }
                        // the boat on its own, for the dates it is free (19.-26.6.2027) or without dates (its size)
                        q.containsKey("yid") && q["startDate"]?.single() !in listOf(null, "2027-06-19") -> """{"content":[],"page":{"totalElements":0}}"""
                        else -> """{"content":[$BALI_6,$SUNDOWNER],"page":{"totalElements":2}}"""
                    }.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    private val tools = AiChatToolExecutor("http://127.0.0.1:${server.address.port}", mapper).fixedToday()
    private val greece = BrandRegistry().get("catamaran-greece").ai

    @AfterEach
    fun stop() = server.stop(0)

    private fun params(raw: String): Map<String, List<String>> =
        raw
            .split('&')
            .filter { it.isNotBlank() }
            .map { it.split('=', limit = 2) }
            .groupBy({ URLDecoder.decode(it[0], StandardCharsets.UTF_8) }, { URLDecoder.decode(it.getOrElse(1) { "" }, StandardCharsets.UTF_8) })

    private fun yachtsQuery(): Map<String, List<String>> = params(seen.last { it.startsWith("/public/yachts?") }.substringAfter('?'))

    /** Every did the search sent, a merged dual-source id ("l-1865,l-259") counted as its parts, as Spring binds it. */
    private fun dids() = yachtsQuery()["did"].orEmpty().flatMap { it.split(',') }

    private fun search(input: String) = tools.searchYachts(mapper.readTree(input), greece)

    private val june = """"countryCode":"GR","startDate":"2027-06-19","endDate":"2027-06-26""""

    @Test
    fun `a marina name searches every marina of that name and the bases nearby, not the first one`() {
        val out = mapper.readTree(search("""{$june,"location":"Lefkas"}""").resultForModel)

        dids() shouldContainAll listOf("l-1865", "l-259", "l-104", "l-835", "l-134")
        dids() shouldNotContain "l-1" // Alimos, 280 km away
        // one id per did: the search splits a merged "l-1865,l-259" only when it is the single did value (review 6.11.2026)
        yachtsQuery()["did"].orEmpty().none { it.contains(',') } shouldBe true
        out.path("searchedArea").asText() shouldContain "Preveza Main Port"
        out.path("searchedArea").asText() shouldContain "Lefkas main port"
    }

    @Test
    fun `two named bases are both searched`() {
        search("""{$june,"locations":["Lefkas","Alimos"]}""")

        dids() shouldContainAll listOf("l-1865", "l-104", "l-835", "l-1")
    }

    @Test
    fun `four long place names still fit the search log's location column`() {
        val long = (1..4).map { "Placename number $it with a long name" }
        search("""{$june,"locations":${mapper.writeValueAsString(long)}}""").search!!.location!!.length shouldBe 80
    }

    @Test
    fun `or similar with two models spans both and searches the models on their own first`() {
        search("""{$june,"models":["Lagoon 46","Bali 4.2"],"orSimilar":true}""")

        val queries = seen.filter { it.startsWith("/public/yachts?") && it.contains("startDate") }.map { params(it.substringAfter('?')) }
        queries.first()["mid"].orEmpty().toSet() shouldBe setOf("4028", "2107", "3001")
        // Lagoon 46 13.99 m and (no Bali 4.2 boat, so the first one) 14.6 m: 45.9 and 47.9 ft, plus and minus 3
        queries.last()["minLength"] shouldBe listOf("42")
        queries.last()["maxLength"] shouldBe listOf("51")
    }

    @Test
    fun `a window search names an unknown model and looks models up once for all its weeks`() {
        val out = mapper.readTree(
            tools.searchYachts(mapper.readTree("""{"countryCode":"GR","windowStart":"2027-06-19","windowEnd":"2027-07-03","models":["Lagoon 46","Nautitech 99"]}"""), greece)
                .resultForModel,
        )
        out.path("modelNote").asText() shouldContain "Nautitech 99"
        out.path("modelFilter").asText() shouldContain "Lagoon 46"
        seen.count { it.startsWith("/public/catalogue/manufacturers") } shouldBe 2
    }

    @Test
    fun `a region is still searched as the region`() {
        search("""{$june,"location":"Ionian"}""")

        dids() shouldBe listOf("55")
    }

    @Test
    fun `a model is a filter, its variants included, a longer number not`() {
        val out = mapper.readTree(search("""{$june,"location":"Lefkas","models":["Lagoon 46"]}""").resultForModel)

        yachtsQuery()["mid"].orEmpty().toSet() shouldBe setOf("4028", "2107")
        out.path("modelFilter").asText() shouldContain "Lagoon 46"
    }

    @Test
    fun `two models are both searched and an unknown one is said`() {
        val out = mapper.readTree(search("""{$june,"models":["Bali 4.2","Lagoon 46","Nautitech 99"]}""").resultForModel)

        yachtsQuery()["mid"].orEmpty().toSet() shouldBe setOf("3001", "4028", "2107")
        out.path("modelNote").asText() shouldContain "Nautitech 99"
    }

    @Test
    fun `or similar searches the model's size band in feet and lists the asked model first`() {
        val out = mapper.readTree(search("""{$june,"location":"Lefkas","models":["Lagoon 46"],"orSimilar":true,"limit":5}""").resultForModel)

        val q = yachtsQuery()
        q.containsKey("mid") shouldBe false
        // Lagoon 46 Sundowner is 13.99 m = 45.9 ft: 42 to 49 ft (the search API's length filter is in feet)
        q["minLength"] shouldBe listOf("42")
        q["maxLength"] shouldBe listOf("49")
        q["vesselType"].orEmpty() shouldContainAll listOf("CATAMARAN")
        out.path("yachts").map { it.path("yacht").asText() } shouldBe listOf("Lagoon 46 Sundowner", "Bali 4.8 Calypso")
        out.path("yachts")[0].path("match").asText() shouldBe "asked model"
        out.path("yachts")[1].path("match").asText() shouldBe "similar size"
    }

    @Test
    fun `an exact cabin count is a minimum and a maximum`() {
        search("""{$june,"minCabins":4,"maxCabins":4}""")

        yachtsQuery()["minCabins"] shouldBe listOf("4")
        yachtsQuery()["maxCabins"] shouldBe listOf("4")
    }

    @Test
    fun `the boat the visitor is looking at is checked for their dates`() {
        val page = "https://www.catamaran-charter-greece.com/catamarans/$SLUG"
        val free = tools.checkBoat(mapper.readTree("""{"startDate":"2027-06-19","endDate":"2027-06-26"}"""), page, greece)
        val result = mapper.readTree(free.resultForModel)

        result.path("available").asBoolean() shouldBe true
        result.path("yacht").asText() shouldBe "Lagoon 46 Sundowner"
        result.path("totalPriceEur").asInt() shouldBe 8721
        result.path("base").asText() shouldBe "Preveza Main Port | Preveza"
        params(seen.last { it.startsWith("/public/yachts?") }.substringAfter('?'))["yid"] shouldBe listOf("2745")
        free.cards!!.size() shouldBe 1
        // a page address in another case is the same boat
        mapper.readTree(tools.checkBoat(mapper.readTree("""{"startDate":"2027-06-19","endDate":"2027-06-26"}"""), page.uppercase().replace("HTTPS://WWW.CATAMARAN-CHARTER-GREECE.COM/CATAMARANS/", "https://www.catamaran-charter-greece.com/catamarans/"), greece).resultForModel)
            .path("available").asBoolean() shouldBe true

        val taken = mapper.readTree(tools.checkBoat(mapper.readTree("""{"boat":"$SLUG","startDate":"2027-07-03","endDate":"2027-07-10"}"""), null, greece).resultForModel)
        taken.path("available").asBoolean() shouldBe false
        taken.has("totalPriceEur") shouldBe false
        taken.path("freeWeeksNearby").map { it.asText() } shouldBe listOf("2027-07-10 to 2027-07-17")
    }

    @Test
    fun `a boat check without a boat, a boat we do not know or one outside the site's fleet is refused`() {
        mapper.readTree(tools.checkBoat(mapper.readTree("""{"startDate":"2027-06-19","endDate":"2027-06-26"}"""), null, greece).resultForModel)
            .path("error").asText() shouldContain "boat"
        mapper.readTree(tools.checkBoat(mapper.readTree("""{"boat":"no-such-boat","startDate":"2027-06-19","endDate":"2027-06-26"}"""), null, greece).resultForModel)
            .has("error") shouldBe true
        mapper.readTree(tools.checkBoat(mapper.readTree("""{"boat":"athens-boat-1","startDate":"2027-06-19","endDate":"2027-06-26"}"""), null, greece).resultForModel)
            .has("error") shouldBe true
        mapper.readTree(tools.checkBoat(mapper.readTree("""{"boat":"$SLUG&yid=1","startDate":"2027-06-19","endDate":"2027-06-26"}"""), null, greece).resultForModel)
            .has("error") shouldBe true
    }

    private companion object {
        const val SLUG = "lagoon-46-sundowner-2745"
        const val D_MARIN = """{"id":"l-1865,l-259","name":"D-Marin Marina Lefkas","locationType":"MARINA","countryCode":"GR","lat":38.830147,"lon":20.711814}"""
        const val LEFKAS_PORT = """{"id":"l-104","name":"Lefkas main port","locationType":"MARINA","countryCode":"GR","lat":38.832537,"lon":20.709771}"""
        const val PREVEZA = """{"id":"l-835,l-134","name":"Preveza Main Port","locationType":"MARINA","countryCode":"GR","lat":38.957648,"lon":20.755213}"""
        const val ALIMOS = """{"id":"l-1","name":"Alimos Marina","locationType":"MARINA","countryCode":"GR","lat":37.91,"lon":23.71}"""
        const val SUNDOWNER =
            """{"id":2745,"slug":"$SLUG","name":"Sundowner","modelName":"Lagoon 46","manufacturerName":"Lagoon","vesselType":"CATAMARAN","length":13.99,"cabins":4,"clientPriceEur":1245.857,"numberOfDays":7,"offerDateFrom":"2027-06-19","offerDateTo":"2027-06-26","location":{"name":"Preveza Main Port | Preveza","countryCode":"GR"}}"""
        const val BALI_6 =
            """{"id":5000,"slug":"bali-4-8-calypso-5000","name":"Calypso","modelName":"Bali 4.8","vesselType":"CATAMARAN","length":14.6,"cabins":4,"clientPriceEur":1908.143,"numberOfDays":7,"offerDateFrom":"2027-06-19","offerDateTo":"2027-06-26","location":{"name":"D-Marin Marina Lefkas | Lefkada","countryCode":"GR"}}"""
        const val DETAIL =
            """{"id":2745,"slug":"$SLUG","name":"Sundowner","modelName":"Lagoon 46","manufacturerName":"Lagoon","vesselType":"CATAMARAN","length":13.99,"cabins":4,"location":{"name":"Preveza Main Port | Preveza","countryCode":"GR"}}"""
    }
}
