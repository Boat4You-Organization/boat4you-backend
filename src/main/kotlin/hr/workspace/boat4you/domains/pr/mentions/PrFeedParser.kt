package hr.workspace.boat4you.domains.pr.mentions

import org.jsoup.Jsoup
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/** One feed item: [url] as the feed has it (PrFeedParser.normaliseUrl makes it the stored form), [title] plain text of at most 500 characters. */
data class FeedItem(
    val url: String,
    val title: String,
    val published: Instant?,
)

/**
 * Google Alerts (Atom) and Talkwalker / other (RSS 2.0) feeds. The XML comes from the internet, so the JDK parser runs
 * with DOCTYPE refused outright (no DTD, so no external entity, XInclude or entity-expansion bomb gets anywhere), every
 * external access off and secure processing on. Pure functions; nothing is fetched here.
 */
object PrFeedParser {
    private const val TITLE_MAX = 500
    private val TRACKING_PARAMS = setOf("fbclid", "gclid")

    /**
     * Atom: each `entry` with its `link` (rel="alternate" or no rel) `href`, `title`, `published` (else `updated`).
     * RSS 2.0: each `channel/item` with its `link` text, `title`, `pubDate` (RFC 1123). Titles lose HTML tags and
     * entities. Entries without a link are skipped; a date that does not parse is null. Throws IllegalArgumentException
     * for anything that is not well-formed XML without a DOCTYPE, or whose root is neither `feed` nor `rss`.
     */
    fun parse(xml: String): List<FeedItem> {
        val doc =
            try {
                builder().parse(InputSource(StringReader(xml.removePrefix("﻿").trimStart())))
            } catch (e: SAXException) {
                throw IllegalArgumentException("not valid XML (${e.message})", e)
            }
        val root = doc.documentElement
        return when (root.localName) {
            "feed" -> children(root, "entry").mapNotNull { atomEntry(it) }
            "rss" -> children(root, "channel").flatMap { children(it, "item") }.mapNotNull { rssItem(it) }
            else -> throw IllegalArgumentException("the root element is <${root.localName ?: root.nodeName}>")
        }
    }

    /**
     * The form a mention URL is stored in, so one article is one row: a `https://www.google.com/url?...` redirect (any
     * google.com host) is replaced by its `url=` (else `q=`) target, percent-decoded once; `utm_*`, `fbclid` and
     * `gclid` parameters (any case) and the #fragment are dropped; scheme and host are lowercased; path case and the other
     * parameters (in their order) stay; an empty `?` goes. Throws IllegalArgumentException unless the result is an
     * http(s) URL with a host and no credentials.
     */
    fun normaliseUrl(raw: String): String {
        val first = uri(raw.trim())
        val target = googleTarget(first)?.let { uri(it.trim()) } ?: first
        val scheme = target.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "only http(s) URLs" }
        val host = target.host?.lowercase(Locale.ROOT)
        require(!host.isNullOrEmpty()) { "URL without a host" }
        require(target.rawUserInfo == null) { "URL with credentials" }
        val port = if (target.port == -1) "" else ":${target.port}"
        val query =
            target.rawQuery
                ?.split('&')
                ?.filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }
                ?.joinToString("&")
                .orEmpty()
        return "$scheme://$host$port${target.rawPath.orEmpty()}" + if (query.isEmpty()) "" else "?$query"
    }

    private fun builder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newDefaultInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().apply {
            // The default handler prints "[Fatal Error] ..." to stderr; the exception carries the message anyway.
            setErrorHandler(SILENT)
            // Unreachable with DOCTYPE refused; a second lock should a parser ever ignore that feature.
            setEntityResolver { _, systemId -> throw SAXException("external entity $systemId refused") }
        }
    }

    private val SILENT =
        object : ErrorHandler {
            override fun warning(e: SAXParseException) = Unit

            override fun error(e: SAXParseException) = Unit

            override fun fatalError(e: SAXParseException) = throw e
        }

    private fun atomEntry(entry: Element): FeedItem? {
        val link =
            children(entry, "link")
                .firstOrNull { it.getAttribute("rel").trim().let { rel -> rel.isEmpty() || rel == "alternate" } && it.getAttribute("href").isNotBlank() }
                ?: return null
        val date = text(entry, "published") ?: text(entry, "updated")
        return FeedItem(link.getAttribute("href").trim(), title(entry), date?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() })
    }

    private fun rssItem(item: Element): FeedItem? {
        // Only the RSS <link>, not an atom:link a feed may add to its items.
        val link = children(item, "link").firstOrNull { it.namespaceURI == null }?.textContent?.trim()?.ifEmpty { null } ?: return null
        val date = text(item, "pubDate")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
        return FeedItem(link, title(item), date)
    }

    private fun title(e: Element): String = Jsoup.parseBodyFragment(text(e, "title").orEmpty()).text().trim().take(TITLE_MAX)

    private fun text(
        e: Element,
        name: String,
    ): String? = children(e, name).firstOrNull()?.textContent?.trim()?.ifEmpty { null }

    private fun children(
        e: Element,
        localName: String,
    ): List<Element> {
        val nodes = e.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { (it.localName ?: it.nodeName) == localName }
    }

    private fun uri(s: String): URI =
        try {
            URI(s)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("not a URL", e)
        }

    /** The target of a google.com/url redirect, decoded once; null when [uri] is not one. */
    private fun googleTarget(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if ((host != "google.com" && !host.endsWith(".google.com")) || uri.rawPath != "/url") return null
        val params = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }
        val value =
            params.firstOrNull { it.first == "url" }?.second?.ifEmpty { null }
                ?: params.firstOrNull { it.first == "q" }?.second?.ifEmpty { null }
                ?: throw IllegalArgumentException("Google redirect without a target")
        return URLDecoder.decode(value, Charsets.UTF_8)
    }

    private fun isTracking(name: String): Boolean = name.lowercase(Locale.ROOT).let { it.startsWith("utm_") || it in TRACKING_PARAMS }
}
