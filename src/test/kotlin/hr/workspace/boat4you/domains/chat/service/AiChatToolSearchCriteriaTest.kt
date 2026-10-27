package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.domains.contactcenter.service.SearchCriteria
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDate

/**
 * What `search_yachts` tells the model and the web widget stays byte for byte as it is today (BACKEND-14, BACKEND-17),
 * and the outcome also carries the whitelisted criteria that were actually sent (BACKEND-11, BACKEND-12). A local HTTP
 * server plays our public API: locations, the amenity catalogue and the yacht search.
 */
class AiChatToolSearchCriteriaTest {
    private val mapper = ObjectMapper()
    private var locations = """{"content":[]}"""
    private var total = 1
    private var down = false
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path
                var status = 200
                val body =
                    when {
                        path.startsWith("/public/locations") -> locations
                        // the executor asks for /public/catalogue/all-amenities, so an exact-path match would miss it
                        path.startsWith("/public/catalogue") -> """[{"labelCode":"air-conditioning","id":5}]"""
                        down -> {
                            status = 500
                            """{"error":"down"}"""
                        }
                        else -> """{"content":[$ROW],"page":{"totalElements":$total}}"""
                    }.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    private val tools = AiChatToolExecutor("http://127.0.0.1:${server.address.port}", mapper)

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `what the model reads and the cards stay exactly as today (BACKEND-14, BACKEND-17)`() {
        val out = tools.searchYachts(mapper.readTree("""{"countryCode":"HR","startDate":"2027-07-03","endDate":"2027-07-10"}"""))
        out.resultForModel shouldBe
            """{"matches":1,"totalAvailable":1,"sortedBy":"price ascending","yachts":[{"yacht":"Lagoon 42 Blue","totalPriceEur":2100,"startDate":"2027-07-03","endDate":"2027-07-10","nights":7,"dates":"exact","year":0,"base":"","url":"https://www.boat4you.com/boat/lagoon-42-blue?startDate=2027-07-03&endDate=2027-07-10&currency=EUR"}]}"""
        mapper.writeValueAsString(out.cards) shouldBe
            """[{"name":"Lagoon 42 Blue","slug":"lagoon-42-blue","imageId":0,"totalPriceEur":2100,"days":7,"year":0,"location":"","startDate":"2027-07-03","endDate":"2027-07-10","dates":"exact"}]"""
        tools.searchYachts(mapper.readTree("""{"countryCode":"H1","startDate":"2027-07-03","endDate":"2027-07-10"}""")).resultForModel shouldBe
            """{"error":"countryCode, startDate and endDate are required"}"""
    }

    private fun search(input: String) = tools.searchYachts(mapper.readTree(input)).search

    private val jul3 = LocalDate.of(2027, 7, 3)
    private val jul10 = LocalDate.of(2027, 7, 10)

    @Test
    fun `a search reports the values it sent, not what the model typed (BACKEND-11, BACKEND-12)`() {
        locations = """{"content":[{"id":17,"name":"Split","locationType":"REGION","countryCode":"HR"}]}"""
        total = 24
        search(
            """{"countryCode":"hr","startDate":"2027-07-03","endDate":"2027-07-10","location":"Split","vesselType":"catamaran","minPersons":8,
            |"minCabins":4,"maxTotalPriceEur":9000,"minTotalPriceEur":0,"sortByPrice":"desc","equipment":["air-conditioning","jacuzzi"]}
            """.trimMargin(),
        ) shouldBe
            SearchCriteria("HR", "Split", 17L, jul3, jul10, 8, 4, null, 9000, "CATAMARAN", listOf("air-conditioning"), 1, 24, "Split (region)")
    }

    @Test
    fun `refused, failed and unresolved searches are reported too`() {
        search("""{"countryCode":"H1","startDate":"2027-07-03","endDate":"2027-07-10","location":"Split&did=1"}""") shouldBe
            SearchCriteria(null, null, null, jul3, jul10, null, null, null, null, null, emptyList(), 0, null, null)
        search("""{"countryCode":"HR","startDate":"2027-07-03","endDate":"2027-07-04"}""")!!.let {
            it.resultCount shouldBe 0
            it.totalAvailable.shouldBeNull()
            it.endDate shouldBe LocalDate.of(2027, 7, 4)
        }
        search("""{"countryCode":"HR","startDate":"03.07.2027","endDate":"2027-07-10"}""")!!.startDate.shouldBeNull()
        search("""{"countryCode":"HR","startDate":"+999999999-07-03","endDate":"2027-07-10"}""")!!.startDate.shouldBeNull()
        down = true
        search("""{"countryCode":"HR","startDate":"2027-07-03","endDate":"2027-07-10"}""") shouldBe
            SearchCriteria("HR", null, null, jul3, jul10, null, null, null, null, null, emptyList(), 0, null, null)
        down = false
        search("""{"countryCode":"HR","startDate":"2027-07-03","endDate":"2027-07-10","location":"Atlantis"}""")!!.let {
            it.location shouldBe "Atlantis"
            it.did.shouldBeNull()
            it.searchedArea.shouldBeNull()
            it.resultCount shouldBe 1
        }
    }

    private companion object {
        const val ROW =
            """{"slug":"lagoon-42-blue","name":"Blue","modelName":"Lagoon 42","clientPriceEur":300,"numberOfDays":7,"offerDateFrom":"2027-07-03","offerDateTo":"2027-07-10"}"""
    }
}
