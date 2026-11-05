package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.service.OpenPageRankClient
import hr.workspace.boat4you.domains.pr.service.PrProperties
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.math.BigDecimal
import java.net.URI

/** Open PageRank (free key, optional): the HTTP call is the `transport` seam, so nothing leaves the machine. */
class OpenPageRankClientTest {
    private val calls = mutableListOf<Pair<URI, String>>()

    private fun client(
        key: String,
        answer: () -> Pair<Int, String>,
    ) = OpenPageRankClient(PrProperties(oprApiKey = key)).apply {
        transport = { uri, apiKey ->
            calls += uri to apiKey
            answer()
        }
    }

    private val okBody = """{"status_code":200,"response":[{"status_code":200,"error":"","page_rank_integer":5,"page_rank_decimal":4.52,"rank":"123","domain":"example-news.co.uk"}],"last_updated":"1st Nov 2026"}"""

    @Test
    fun oprSkippedWithoutKey() {
        client("") { 200 to okBody }.rank("example-news.co.uk") shouldBe null
        client("   ") { 200 to okBody }.rank("example-news.co.uk") shouldBe null
        calls.shouldBeEmpty()
    }

    @Test
    fun oprReturnsPageRankDecimal() {
        client("k-123") { 200 to okBody }.rank("example-news.co.uk") shouldBe BigDecimal("4.52")

        calls shouldContainExactly listOf(URI("https://openpagerank.com/api/v1.0/getPageRank?domains%5B%5D=example-news.co.uk") to "k-123")
    }

    @Test
    fun oprFailureIsNullNeverAnError() {
        client("k") { 500 to okBody }.rank("a.example") shouldBe null
        client("k") { 401 to """{"error":"bad key"}""" }.rank("a.example") shouldBe null
        client("k") { 200 to """{"status_code":200,"response":[{"status_code":404,"error":"Domain not found","page_rank_decimal":0,"domain":"a.example"}]}""" }
            .rank("a.example") shouldBe null
        client("k") { 200 to """{"status_code":200,"response":[]}""" }.rank("a.example") shouldBe null
        client("k") { 200 to """{"status_code":200,"response":[{"status_code":200,"page_rank_decimal":"n/a"}]}""" }.rank("a.example") shouldBe null
        client("k") { 200 to "<html>not json</html>" }.rank("a.example") shouldBe null
        client("k") { throw IOException("timeout") }.rank("a.example") shouldBe null
        client("k") { 200 to okBody }.rank(" ") shouldBe null
    }
}
