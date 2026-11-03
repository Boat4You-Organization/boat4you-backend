package hr.workspace.boat4you.domains.contactcenter.rec

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * The positional extractor of the P0 spike (S12, 0 mismatches against clarity-decode 0.8.73), for the events the Contact Center
 * keeps: 0 Metric, 8 Document, 9 Click, 10 Scroll, 11 Resize, 29 Navigation, 31 ScriptError, 36 Summary; plus the envelope and
 * the 34 Variable events (the widget's `ccv` = our visit key). A field whose index does not exist is left out, as the decoder does.
 *
 * Envelope `e`: 0 version, 1 sequence, 2 start, 3 duration, 4 projectId, 5 userId, 6 sessionId, 7 pageNum, 8 upload, 9 end,
 * 10 platform, 11 url (the page view's first url, F2e). Events are `[time, type, ...tokens]` in `a`, then `p` (omitted when empty).
 */
object RecExtractor {
    const val EVENT_METRIC = 0
    const val EVENT_MUTATION = 6
    const val EVENT_DOCUMENT = 8
    const val EVENT_CLICK = 9
    const val EVENT_SCROLL = 10
    const val EVENT_RESIZE = 11
    const val EVENT_NAVIGATION = 29
    const val EVENT_SCRIPT_ERROR = 31
    const val EVENT_VARIABLE = 34
    const val EVENT_SUMMARY = 36

    /** Metric id of TotalBytes (clarity-js types/data.d.ts, Data.Metric). */
    private const val METRIC_TOTAL_BYTES = "2"

    private val WANTED =
        setOf(EVENT_METRIC, EVENT_DOCUMENT, EVENT_CLICK, EVENT_SCROLL, EVENT_RESIZE, EVENT_NAVIGATION, EVENT_SCRIPT_ERROR, EVENT_SUMMARY)

    private val mapper = ObjectMapper()

    /** Navigation (performance.ts) tokens 2 onwards, in order. */
    private val NAVIGATION_FIELDS =
        listOf(
            "fetchStart",
            "connectStart",
            "connectEnd",
            "requestStart",
            "responseStart",
            "responseEnd",
            "domInteractive",
            "domComplete",
            "loadEventStart",
            "loadEventEnd",
            "redirectCount",
            "size",
            "type",
            "protocol",
            "encodedSize",
            "decodedSize",
        )

    data class Envelope(
        val version: String,
        val sequence: Int,
        val start: Long,
        val duration: Long,
        val projectId: String,
        val userId: String,
        val sessionId: String,
        val pageNum: Int,
        val end: Boolean,
        val url: String?,
    )

    data class Event(
        val type: Int,
        val time: Long?,
        val data: Map<String, Any?>,
    )

    data class Payload(
        val envelope: Envelope,
        val events: List<Event>,
        val variables: Map<String, List<String>>,
        /** Times of the DOM changes (Mutation events), for telling a click the page reacted to from a dead one. */
        val mutations: List<Long> = emptyList(),
    )

    /** One payload (one line of a recording): its envelope, the kept events in arrival order, and its variables. */
    fun parse(payloadJson: String): Payload {
        val (e, entries) = read(payloadJson)
        val events = ArrayList<Event>()
        val variables = LinkedHashMap<String, List<String>>()
        val mutations = ArrayList<Long>()
        for (entry in entries) {
            @Suppress("UNCHECKED_CAST")
            val t = entry as? Tokens ?: continue
            val type = eventType(t) ?: continue
            if (type == EVENT_MUTATION) {
                (t.getOrNull(0) as? Number)?.toLong()?.let(mutations::add)
                continue
            }
            if (type == EVENT_VARIABLE) {
                var v = 2
                while (v + 1 < t.size) {
                    val values = t[v + 1]
                    variables[jsKey(t[v])] = if (values is List<*>) values.map { it.toString() } else listOf(values.toString())
                    v += 2
                }
                continue
            }
            decode(type, t, payloadJson)?.let { events += Event(type, (t.getOrNull(0) as? Number)?.toLong(), it) }
        }
        return Payload(envelope(e), events, variables, mutations)
    }

    /** Only the envelope of a payload: the worker routes and dedupes on it without decoding the events. */
    fun envelopeOf(payloadJson: String): Envelope {
        val e = mapper.readValue(payloadJson, Map::class.java)["e"] as? List<*> ?: error("payload has no envelope array e")
        return envelope(e)
    }

    /** The spike's parity records `{file, seq, event, time, data}`, in arrival order (tools/parity.mjs format). */
    fun records(
        file: String,
        payloadJson: String,
    ): List<Map<String, Any?>> {
        val (e, entries) = read(payloadJson)
        val out = ArrayList<Map<String, Any?>>()
        for (entry in entries) {
            @Suppress("UNCHECKED_CAST")
            val t = entry as? Tokens ?: continue
            val event = eventType(t) ?: continue
            val data = decode(event, t, payloadJson) ?: continue
            val record = LinkedHashMap<String, Any?>()
            record["file"] = file
            if (e.size > 1) record["seq"] = e[1]
            record["event"] = event
            if (t.isNotEmpty()) record["time"] = t[0]
            record["data"] = data
            out.add(record)
        }
        return out
    }

    private fun read(payloadJson: String): Pair<List<*>, List<*>> {
        val root = mapper.readValue(payloadJson, Map::class.java)
        val e = root["e"] as? List<*> ?: error("payload has no envelope array e")
        val a = root["a"] as? List<*> ?: error("payload has no event array a")
        val p = root["p"] as? List<*> // clarity omits p when it is empty
        return e to (if (p != null) a + p else a)
    }

    private fun envelope(e: List<*>) =
        Envelope(
            version = e.getOrNull(0)?.toString().orEmpty(),
            sequence = (e.getOrNull(1) as? Number)?.toInt() ?: 0,
            start = (e.getOrNull(2) as? Number)?.toLong() ?: 0,
            duration = (e.getOrNull(3) as? Number)?.toLong() ?: 0,
            projectId = e.getOrNull(4)?.toString().orEmpty(),
            userId = e.getOrNull(5)?.toString().orEmpty(),
            sessionId = e.getOrNull(6)?.toString().orEmpty(),
            pageNum = (e.getOrNull(7) as? Number)?.toInt() ?: 0,
            end = (e.getOrNull(9) as? Number)?.toInt() == 1,
            url = e.getOrNull(11) as? String,
        )

    private fun decode(
        event: Int,
        t: Tokens,
        payloadJson: String,
    ): Map<String, Any?>? =
        if (event !in WANTED) {
            null
        } else {
            when (event) {
                EVENT_METRIC -> decodeMetric(t, payloadJson)
                EVENT_DOCUMENT, EVENT_RESIZE -> decodeSize(t)
                EVENT_CLICK -> decodeClick(t)
                EVENT_SCROLL -> decodeScroll(t)
                EVENT_NAVIGATION -> decodeNavigation(t)
                EVENT_SCRIPT_ERROR -> decodeScriptError(t)
                else -> decodeSummary(t)
            }
        }

    // `tokens[i]`, but only when the index exists.
    private fun MutableMap<String, Any?>.at(
        name: String,
        t: Tokens,
        i: Int,
    ) {
        if (i < t.size) this[name] = t[i]
    }

    // `tokens.length > i ? tokens[i] : fallback`
    private fun MutableMap<String, Any?>.atOr(
        name: String,
        t: Tokens,
        i: Int,
        fallback: Any?,
    ) {
        this[name] = if (t.size > i) t[i] else fallback
    }

    // A JS property key: the number 5 becomes "5", as in `metrics[tokens[m++]]`.
    private fun jsKey(token: Any?): String =
        when (token) {
            is String -> token
            is Number -> {
                val d = token.toDouble()
                if (d == Math.floor(d) && !d.isInfinite()) token.toLong().toString() else token.toString()
            }
            null -> "null"
            is Boolean -> token.toString()
            else -> error("unsupported key token $token")
        }

    // `switch (entry[1])` in clarity.ts compares with ===, so only an integral number matches an event id.
    private fun eventType(t: Tokens): Int? {
        val n = t.getOrNull(1) as? Number ?: return null
        val d = n.toDouble()
        return if (d == Math.floor(d) && !d.isInfinite()) d.toInt() else null
    }

    private fun decodeMetric(
        t: Tokens,
        payloadJson: String,
    ): Map<String, Any?> {
        val metrics = LinkedHashMap<String, Any?>()
        var m = 2
        while (m < t.size) {
            val key = jsKey(t[m])
            m++
            // `metrics[key] = tokens[m++]` stores undefined for an odd token count, which JSON drops.
            if (m < t.size) metrics[key] = t[m] else metrics.remove(key)
            m++
        }
        // clarity.ts: the byte count of the payload is not in the event, decode sets it from the input string's length
        // (UTF-16 code units, which String.length also counts).
        metrics[METRIC_TOTAL_BYTES] = payloadJson.length
        return metrics
    }

    private fun decodeSummary(t: Tokens): Map<String, Any?> {
        val summary = LinkedHashMap<String, Any?>()
        var s = 2
        while (s < t.size) {
            val key = jsKey(t[s])
            s++
            val values = t.getOrNull(s) as? List<*> ?: error("summary key $key has no value array")
            s++
            val pairs = ArrayList<List<Any?>>()
            var i = 0
            while (i < values.size - 1) {
                pairs.add(listOf(values[i], values[i + 1]))
                i += 2
            }
            summary[key] = pairs
        }
        return summary
    }

    private fun decodeClick(t: Tokens): Map<String, Any?> {
        // tokens[12] is "<hash>.<hashBeta>"; decode would throw on a missing or non-string value.
        val hashes = (t.getOrNull(12) as? String ?: error("click has no hash string at index 12")).split('.')
        val d = LinkedHashMap<String, Any?>()
        d.at("target", t, 2)
        d.at("x", t, 3)
        d.at("y", t, 4)
        d.at("eX", t, 5)
        d.at("eY", t, 6)
        d.at("button", t, 7)
        d.at("reaction", t, 8)
        d.at("context", t, 9)
        d.at("text", t, 10)
        d.at("link", t, 11)
        d["hash"] = hashes[0]
        if (hashes.size > 1) d["hashBeta"] = hashes[1]
        d.atOr("trust", t, 13, 1) // Data.BooleanFlag.True
        d.atOr("isFullText", t, 14, null)
        d.atOr("w", t, 15, 0)
        d.atOr("h", t, 16, 0)
        d.atOr("tag", t, 17, null)
        d.atOr("class", t, 18, null)
        d.atOr("id", t, 19, null)
        d.atOr("source", t, 20, null)
        return d
    }

    private fun decodeScroll(t: Tokens): Map<String, Any?> {
        val d = LinkedHashMap<String, Any?>()
        d.at("target", t, 2)
        d.at("x", t, 3)
        d.at("y", t, 4)
        d.atOr("top", t, 5, null)
        d.atOr("bottom", t, 6, null)
        d.atOr("trust", t, 7, 1) // Data.BooleanFlag.True
        return d
    }

    private fun decodeSize(t: Tokens): Map<String, Any?> {
        // Resize (interaction.ts) and Document (layout.ts) both map tokens 2 and 3 to width and height.
        val d = LinkedHashMap<String, Any?>()
        d.at("width", t, 2)
        d.at("height", t, 3)
        return d
    }

    private fun decodeNavigation(t: Tokens): Map<String, Any?> {
        val d = LinkedHashMap<String, Any?>()
        NAVIGATION_FIELDS.forEachIndexed { i, name -> d.at(name, t, i + 2) }
        return d
    }

    private fun decodeScriptError(t: Tokens): Map<String, Any?> {
        val d = LinkedHashMap<String, Any?>()
        d.at("message", t, 2)
        d.at("line", t, 3)
        d.at("column", t, 4)
        d.at("stack", t, 5)
        d.at("source", t, 6)
        return d
    }
}

private typealias Tokens = List<Any?>
