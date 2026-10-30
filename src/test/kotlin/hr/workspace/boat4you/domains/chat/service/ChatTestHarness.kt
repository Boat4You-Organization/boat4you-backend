package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSearch
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSearchRepository
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEvent
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEventRepository
import hr.workspace.boat4you.domains.contactcenter.service.AiCircuitBreaker
import hr.workspace.boat4you.domains.contactcenter.service.CcAiSettings
import hr.workspace.boat4you.domains.contactcenter.service.ChatSearchRecorder
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one fixture of the chat tests: in-memory repositories that answer by method name, a scripted [AnthropicClient],
 * mail capture, a blog site with one guide, the `cc_search` recorder and a transaction manager that only counts
 * transactions. With [fleetRow] a local fleet API answers every request with that one row and the executor talks to
 * it; without it the executor points at a closed port. Extend it (`class FooTest : ChatTestHarness()`, `@AfterEach`
 * calling [close]) or hold it (`private val h = ChatTestHarness()`).
 */
open class ChatTestHarness(
    fleetRow: String? = null,
) : AutoCloseable {
    val mapper = ObjectMapper()
    val sessionStore = mutableMapOf<Long, AiChatSession>()
    val stored = mutableListOf<AiChatMessage>()
    var nextSessionId = 100L
    var nextMessageId = 1L

    /** What `countByIpAndFlaggedTrueAndLockedAtAfter` answers: sessions of the visitor's address that are locked. */
    var flaggedFromIp = 0L

    /** Per model call: the system prompt (both parts), the messages and the tools, in call order (messages are deep copies). */
    val modelSystems = mutableListOf<AnthropicClient.SystemPrompt>()
    val modelMessages = mutableListOf<JsonNode>()
    val modelTools = mutableListOf<JsonNode?>()
    val modelCalls: List<Pair<String, JsonNode>> get() = modelSystems.map { it.full }.zip(modelMessages)
    var modelReply: () -> JsonNode = { textReply("Hi, I'm IRIS! Where would you like to sail?") }
    val mails = CopyOnWriteArrayList<InvocationOnMock>()

    /** Number of the transaction a write ran in: 0 before the first one, unchanged while the model loop runs without one. */
    var txSeq = 0
    val savedInTx = mutableMapOf<Long, Int>()
    val sessionSavedInTx = mutableListOf<Int>()
    val firstResponseStamps = mutableListOf<List<Any?>>()

    /** Names of the broker-reply UPDATEs adminReply ran, in order (P2). */
    val humanReplyUpdates = mutableListOf<String>()

    /** Every `cc_search` row the recorder saves, with the number of the transaction it was saved in. */
    val recorded = mutableListOf<Pair<Int, CcSearch>>()

    val sessions: AiChatSessionRepository =
        mock(AiChatSessionRepository::class.java) { inv ->
            when (inv.method.name) {
                "save" ->
                    (inv.arguments[0] as AiChatSession).also { s ->
                        if (s.id == null) s.id = nextSessionId++
                        sessionStore[s.id!!] = s
                        sessionSavedInTx += txSeq
                    }
                "countByIpAndFlaggedTrueAndLockedAtAfter" -> flaggedFromIp
                "findById" -> Optional.ofNullable(sessionStore[inv.arguments[0] as Long])
                "findAllByStatusAndFlaggedFalseAndDeletedAtIsNullAndHandoffHoldingAtIsNullAndHandoffRequestedAtBetween" ->
                    sessionStore.values.filter {
                        val at = it.handoffRequestedAt
                        it.status == inv.arguments[0] && !it.flagged && it.deletedAt == null && it.handoffHoldingAt == null && at != null &&
                            !at.isBefore(inv.arguments[1] as Instant) && !at.isAfter(inv.arguments[2] as Instant)
                    }
                "findAllByStatusAndFlaggedFalseAndDeletedAtIsNullAndLastActivityAtAfter" ->
                    sessionStore.values.filter {
                        it.status == inv.arguments[0] && !it.flagged && it.deletedAt == null && it.lastActivityAt.isAfter(inv.arguments[1] as Instant)
                    }
                // The two conditional UPDATEs of the never-silent path: same WHERE and SET as the queries, 1 or 0 rows.
                "stampHandoffHolding" -> {
                    val s = sessionStore[inv.arguments[0] as Long]
                    if (s != null && s.status == AiChatSession.STATUS_HUMAN_REQUESTED && !s.flagged && s.deletedAt == null && s.handoffHoldingAt == null) {
                        s.handoffHoldingAt = inv.arguments[1] as Instant
                        1
                    } else {
                        0
                    }
                }
                "returnToAi" -> {
                    val s = sessionStore[inv.arguments[0] as Long]
                    if (s != null && s.status == AiChatSession.STATUS_HUMAN && !s.flagged && s.deletedAt == null) {
                        s.status = AiChatSession.STATUS_AI
                        s.aiReturnedAt = inv.arguments[1] as Instant
                        1
                    } else {
                        0
                    }
                }
                // P2: take over, the broker reply's own UPDATE and its response stamps, same WHERE and SET as the queries.
                "takeOver" -> {
                    val s = sessionStore[inv.arguments[0] as Long]
                    if (s != null && s.status in TAKE_OVER_FROM && s.deletedAt == null) {
                        s.status = AiChatSession.STATUS_HUMAN
                        if (s.takenOverAt == null) s.takenOverAt = inv.arguments[1] as Instant
                        1
                    } else {
                        0
                    }
                }
                "markHumanReply" -> {
                    humanReplyUpdates += inv.method.name
                    1
                }
                "stampHumanReply" -> {
                    humanReplyUpdates += inv.method.name
                    1
                }
                "stampFirstResponse" -> {
                    firstResponseStamps += inv.arguments.toList()
                    null
                }
                else -> null
            }
        }
    val messages: AiChatMessageRepository =
        mock(AiChatMessageRepository::class.java) { inv ->
            when (inv.method.name) {
                "save" ->
                    (inv.arguments[0] as AiChatMessage).also { m ->
                        m.id = nextMessageId++
                        stored += m
                        savedInTx[m.id!!] = txSeq
                    }
                "countBySessionId" -> stored.count { it.sessionId == inv.arguments[0] }.toLong()
                "findAllBySessionIdOrderByIdAsc" -> stored.filter { it.sessionId == inv.arguments[0] }
                "findFirstBySessionIdOrderByIdDesc" -> stored.filter { it.sessionId == inv.arguments[0] }.maxByOrNull { it.id!! }
                "findBySessionIdAndClientMessageId" ->
                    stored.firstOrNull { it.sessionId == inv.arguments[0] && it.clientMessageId == inv.arguments[1] }
                "findAllBySessionIdAndIdGreaterThanOrderByIdAsc" ->
                    stored.filter { it.sessionId == inv.arguments[0] && it.id!! > inv.arguments[1] as Long }
                else -> null
            }
        }
    private val anthropic =
        mock(AnthropicClient::class.java) { inv ->
            when (inv.method.name) {
                "isEnabled" -> true
                "createMessage" -> {
                    modelSystems += inv.arguments[0] as AnthropicClient.SystemPrompt
                    modelMessages.add((inv.arguments[1] as JsonNode).deepCopy())
                    modelTools += inv.arguments[2] as JsonNode?
                    modelReply()
                }
                else -> null
            }
        }
    private val emailService =
        mock(EmailService::class.java) { inv ->
            if (inv.method.name == "sendEmail") mails += inv
            null
        }
    private val geoIp = mock(GeoIpService::class.java) { null }
    private val txManager =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                txSeq++
                return SimpleTransactionStatus()
            }

            override fun commit(status: TransactionStatus) = Unit

            override fun rollback(status: TransactionStatus) = Unit
        }

    /** The site serving one blog guide (sitemap + page title) for the blog index. */
    private val blogSite =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body =
                    when (ex.requestURI.path) {
                        "/sitemap-blogs.xml" -> "<urlset><url><loc>https://www.boat4you.com/blog/7-day-itinerary-from-split-croatia</loc></url></urlset>"
                        else -> "<title>7-Day Itinerary from Split | Boat4You</title>"
                    }.toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    private val blogIndex = ChatBlogIndex("http://127.0.0.1:${blogSite.address.port}").also { it.refresh() }

    @Suppress("UNCHECKED_CAST")
    val ccSearches: CcSearchRepository =
        mock(CcSearchRepository::class.java) { inv ->
            if (inv.method.name == "saveAll") (inv.arguments[0] as Iterable<CcSearch>).onEach { recorded += txSeq to it }.toList() else null
        }

    /** Our public API for the executor: every request answers `{"content":[fleetRow],"page":{"totalElements":1}}`. Null without [fleetRow]. */
    private val fleet: HttpServer? =
        fleetRow?.let { row ->
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { ex ->
                    val body = """{"content":[$row],"page":{"totalElements":1}}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                start()
            }
        }

    /** Every row of the security log, from the breaker and from the chat service. */
    val events = CopyOnWriteArrayList<CcSecurityEvent>()
    val securityEvents: CcSecurityEventRepository =
        mock(CcSecurityEventRepository::class.java) { inv ->
            when (inv.method.name) {
                "save" -> (inv.arguments[0] as CcSecurityEvent).also { events += it }
                "latestBreakerEventPerScope" -> emptyList<CcSecurityEvent>()
                else -> null
            }
        }

    /** What the `cc.*` AI settings answer; the defaults unless a test changes them. */
    var settingsValues = CcAiSettings.Values()
    val settings =
        object : CcAiSettings {
            override fun current() = settingsValues

            override fun update(
                key: String,
                value: String?,
            ) = false
        }

    /** The breaker's own mails, so the chat's mail counts ([mails]) stay exact when a test opens it. */
    val breakerMails = CopyOnWriteArrayList<InvocationOnMock>()
    private val breakerEmail =
        mock(EmailService::class.java) { inv ->
            if (inv.method.name == "sendEmail") breakerMails += inv
            null
        }
    val breaker = AiCircuitBreaker(settings, securityEvents, breakerEmail, "info@example.com").also { it.mailExecutor = { r -> r.run() } }

    val service =
        AiChatService(
            sessions = sessions,
            messages = messages,
            anthropic = anthropic,
            tools = AiChatToolExecutor("http://127.0.0.1:${fleet?.address?.port ?: 1}", mapper),
            objectMapper = mapper,
            emailService = emailService,
            geoIp = geoIp,
            blogIndex = blogIndex,
            searchRecorder = ChatSearchRecorder(ccSearches),
            brands = BrandRegistry(),
            breaker = breaker,
            securityEvents = securityEvents,
            transactionManager = txManager,
            notifyEmail = "info@example.com",
        )

    fun textReply(text: String): JsonNode = mapper.readTree("""{"stop_reason":"end_turn","content":[{"type":"text","text":${mapper.writeValueAsString(text)}}]}""")

    fun toolCall(
        name: String,
        input: String,
    ): JsonNode {
        val block = """{"type":"tool_use","id":"t1","name":"$name","input":$input}"""
        return mapper.readTree("""{"stop_reason":"tool_use","content":[$block]}""")
    }

    fun newSession(
        status: String = AiChatSession.STATUS_AI,
        locale: String = "en",
        name: String? = "Ana",
        siteId: String = "boat4you",
    ) = AiChatSession()
        .apply {
            id = nextSessionId++
            token = "tok-$id"
            this.locale = locale
            this.status = status
            visitorName = name
            ip = "10.0.0.1"
            this.siteId = siteId
            lastActivityAt = Instant.now()
        }.also { sessionStore[it.id!!] = it }

    /** Stores a message of [role] that is [minutesAgo] minutes old. */
    fun addMessage(
        s: AiChatSession,
        role: String,
        content: String,
        minutesAgo: Long,
    ) = AiChatMessage()
        .apply {
            id = nextMessageId++
            sessionId = s.id
            this.role = role
            this.content = content
            createdAt = Instant.now().minus(Duration.ofMinutes(minutesAgo))
        }.also { stored += it }

    fun transcript(s: AiChatSession) = stored.filter { it.sessionId == s.id }.map { it.role to it.content }

    fun awaitMails(count: Int) {
        val until = System.currentTimeMillis() + 3000
        while (mails.size < count && System.currentTimeMillis() < until) Thread.sleep(20)
        Thread.sleep(200)
    }

    /** Stops the blog site and the fleet API. */
    override fun close() {
        blogSite.stop(0)
        fleet?.stop(0)
    }
}

/** Statuses [AiChatSessionRepository.takeOver] takes a conversation from. */
private val TAKE_OVER_FROM = setOf("AI", "HUMAN_REQUESTED", "HUMAN", "CLOSED")
