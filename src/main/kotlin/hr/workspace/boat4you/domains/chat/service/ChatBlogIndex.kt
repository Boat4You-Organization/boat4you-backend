package hr.workspace.boat4you.domains.chat.service

import org.apache.commons.text.StringEscapeUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The boat4you blog guides the chat may point a visitor to (Mario 13.10.2026: "imamo blog baš o tome, pogledajte").
 * Source = the live site itself: the slugs from www.boat4you.com/sitemap-blogs.xml (only published posts) and each
 * post's <title> from its page - WordPress answers 403 to anything but the web server. Refreshed in the background
 * every [REFRESH_EVERY]; a failed refresh keeps the last good list. The model only gets slug + title and can only
 * show a guide by its slug (show_blog_posts), so every link the visitor sees is a real, published post.
 */
@Component
class ChatBlogIndex(
    @Value("\${chat.site-base-url:https://www.boat4you.com}") private val siteBaseUrl: String,
) {
    data class BlogPost(
        val slug: String,
        val title: String,
    ) {
        /** Site-relative link, the widget renders it as a card under the reply. */
        val path: String get() = "/blog/$slug"
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val client: RestClient =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(5))
                    setReadTimeout(Duration.ofSeconds(10))
                },
            ).defaultHeader("User-Agent", "boat4you-chat-blog-index")
            .build()

    @Volatile
    private var posts: List<BlogPost> = emptyList()

    @Volatile
    private var loadedAt: Instant? = null
    private val refreshing = AtomicBoolean(false)

    /** The current guides (newest first, as the sitemap lists them); starts a background refresh when stale. */
    fun posts(): List<BlogPost> {
        if (loadedAt?.isAfter(Instant.now().minus(REFRESH_EVERY)) != true) refreshInBackground()
        return posts
    }

    fun bySlug(slug: String): BlogPost? = posts.firstOrNull { it.slug == slug }

    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() = refreshInBackground()

    private fun refreshInBackground() {
        if (!refreshing.compareAndSet(false, true)) return
        Thread({
            try {
                refresh()
            } catch (e: Exception) {
                log.warn("chat blog index refresh failed: ${e.message}")
            } finally {
                refreshing.set(false)
            }
        }, "chat-blog-index").apply { isDaemon = true }.start()
    }

    internal fun refresh() {
        val sitemap = client.get().uri("$siteBaseUrl/sitemap-blogs.xml").retrieve().body(String::class.java).orEmpty()
        val slugs = SITEMAP_SLUG.findAll(sitemap).map { it.groupValues[1] }.distinct().take(MAX_POSTS).toList()
        if (slugs.isEmpty()) return
        val known = posts.associateBy { it.slug }
        posts = slugs.map { slug -> known[slug] ?: BlogPost(slug, fetchTitle(slug) ?: titleFromSlug(slug)) }
        loadedAt = Instant.now()
        log.info("chat blog index: {} guides", posts.size)
    }

    private fun fetchTitle(slug: String): String? =
        runCatching { client.get().uri("$siteBaseUrl/blog/$slug").retrieve().body(String::class.java) }
            .getOrNull()
            ?.let { PAGE_TITLE.find(it)?.groupValues?.get(1) }
            ?.let { cleanTitle(it) }

    internal companion object {
        val REFRESH_EVERY: Duration = Duration.ofHours(6)
        const val MAX_POSTS = 150
        private const val MAX_TITLE = 140

        private val SITEMAP_SLUG = Regex("""<loc>\s*https?://[^<\s]*/blog/([a-z0-9-]{1,200})/?\s*</loc>""")
        private val PAGE_TITLE = Regex("""<title[^>]*>([^<]{1,400})</title>""")
        private val SITE_SUFFIX = Regex("""\s*[|\x{2013}\x{2014}-]\s*boat4you\s*$""", RegexOption.IGNORE_CASE)
        private val UNSAFE = Regex("""[\p{Cc}<>{}\[\]`]""")

        /** "Croatia vs Greece Sailing: Prices Compared | Boat4You" -> "Croatia vs Greece Sailing: Prices Compared". */
        fun cleanTitle(raw: String): String? =
            StringEscapeUtils
                .unescapeHtml4(raw)
                .replace(SITE_SUFFIX, "")
                .replace(UNSAFE, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(MAX_TITLE)
                .ifBlank { null }

        fun titleFromSlug(slug: String): String = slug.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }

        /** Pure parse of a sitemap for tests. */
        fun slugsOf(sitemap: String): List<String> = SITEMAP_SLUG.findAll(sitemap).map { it.groupValues[1] }.distinct().toList()
    }
}
