package ai.jarvis.tools

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Live internet access, executed inside the app.
 *
 * These tools fetch the data themselves and hand the text back to the model, so
 * a question like "what is a quark" is answered in the conversation instead of
 * opening a browser. Nothing here needs an API key: DuckDuckGo's HTML endpoint
 * and the Wikipedia API are public.
 *
 * Both tools are read-only and their output is bounded, so a huge page cannot
 * crowd the prompt.
 */
class WebSearchTool :
    Tool(
        ToolSpec(
            name = "web_search",
            description =
                "Search the live internet and get the results back as text. Use this for any " +
                    "question about facts, people, places, news, prices, definitions or anything " +
                    "you are not certain about, then answer the user directly. Do not open the " +
                    "browser for this.",
            parameters = schema("query" to stringProp("What to search for")),
            required = listOf("query"),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolResult.fail("What should I look up?")
        val results = WebLookup.search(query)
        if (results.isEmpty()) {
            return ToolResult.fail("I could not reach any search source. Check the internet connection.")
        }
        val body =
            buildString {
                append("Search results for \"").append(query).append("\":\n")
                results.take(5).forEachIndexed { index, item ->
                    append(index + 1).append(". ").append(item.title).append('\n')
                    if (item.url.isNotBlank()) append("   ").append(item.url).append('\n')
                    if (item.snippet.isNotBlank()) append("   ").append(item.snippet).append('\n')
                }
                append("\nAnswer from these results and name the source.")
            }.take(MAX_CHARS)
        return ToolResult.ok(
            body,
            JSONObject().apply {
                put("query", query)
                put("count", results.size)
            },
        )
    }

    private companion object {
        const val MAX_CHARS = 2_500
    }
}

/** Fetches one page and returns its readable text, with no browser involved. */
class ReadPageTool :
    Tool(
        ToolSpec(
            name = "read_page",
            description =
                "Fetch a web page and return its readable text. Use it after web_search when " +
                    "the snippets are not enough, or when the user gives a link.",
            parameters = schema("url" to stringProp("Full http or https URL")),
            required = listOf("url"),
        ),
    ) {
    override fun execute(
        context: Context,
        args: JSONObject,
    ): ToolResult {
        val url = args.optString("url").trim()
        if (!WebLookup.isWebUrl(url)) return ToolResult.fail("Only http and https URLs can be read.")
        val page =
            WebLookup.fetch(url)
                ?: return ToolResult.fail("That page could not be loaded.")
        val text = WebLookup.readableText(page).take(MAX_CHARS)
        if (text.length < 40) return ToolResult.fail("That page had no readable text.")
        return ToolResult.ok(
            "Content of $url:\n$text",
            JSONObject().apply {
                put("url", url)
                put("chars", text.length)
            },
        )
    }

    private companion object {
        const val MAX_CHARS = 4_000
    }
}

internal data class WebHit(
    val title: String,
    val url: String,
    val snippet: String,
)

/**
 * Deliberately small and dependency-free: one HTML endpoint with two mirrors,
 * then the Wikipedia API when the search engine throttles the request.
 */
internal object WebLookup {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0 Mobile Safari/537.36"

    fun isWebUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    fun search(query: String): List<WebHit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val fromDuck = parseDuckDuckGo(fetch("https://html.duckduckgo.com/html/?q=$encoded"))
        if (fromDuck.size >= 2) return fromDuck
        // The HTML endpoint sometimes answers with an anti-bot challenge, so an
        // encyclopaedic source and a news feed carry the question when that
        // happens. Both are public and need no key.
        val fallback = searchWikipedia(query) + searchNews(query)
        return (fromDuck + fallback).distinctBy { it.url }.filter { it.title.isNotBlank() }
    }

    fun fetch(url: String): String? {
        if (!isWebUrl(url)) return null
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.8")
                }
            // 202 is DuckDuckGo's challenge page, not content.
            if (connection.responseCode != 200) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    fun readableText(html: String): String =
        html
            .replace(Regex("(?is)<(script|style|noscript|svg|head).*?</\\1>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6]|tr|section|article)>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .let(::decodeEntities)
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    // -----------------------------------------------------------------------

    private fun parseDuckDuckGo(html: String?): List<WebHit> {
        if (html.isNullOrBlank()) return emptyList()
        val links =
            Regex(
                "(?is)<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            ).findAll(html).toList()
        if (links.isEmpty()) return emptyList()
        val snippets =
            Regex("(?is)<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>")
                .findAll(html).map { readableText(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { index, match ->
            val url = unwrapDuckLink(match.groupValues[1])
            val title = readableText(match.groupValues[2])
            if (url.isBlank() || title.isBlank()) return@mapIndexedNotNull null
            WebHit(title, url, snippets.getOrElse(index) { "" }.take(300))
        }
    }

    /** DuckDuckGo wraps results as `/l/?uddg=<encoded target>`. */
    private fun unwrapDuckLink(raw: String): String {
        val href = raw.replace("&amp;", "&")
        val target =
            Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)
                ?: return if (isWebUrl(href)) href else ""
        return runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault("")
    }

    private fun searchWikipedia(query: String): List<WebHit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchJson =
            fetch("https://en.wikipedia.org/w/rest.php/v1/search/page?q=$encoded&limit=3")
                ?: return emptyList()
        val pages =
            runCatching { JSONObject(searchJson).optJSONArray("pages") }.getOrNull()
                ?: return emptyList()
        val hits =
            (0 until pages.length()).mapNotNull { index ->
                val page = pages.optJSONObject(index) ?: return@mapNotNull null
                val title = page.optString("title").trim()
                if (title.isBlank()) return@mapNotNull null
                val key = page.optString("key").ifBlank { title.replace(' ', '_') }
                val snippet =
                    listOf(page.optString("description"), readableText(page.optString("excerpt")))
                        .filter { it.isNotBlank() }
                        .joinToString(" — ")
                WebHit(title, "https://en.wikipedia.org/wiki/$key", snippet.take(300))
            }
        if (hits.isEmpty()) return emptyList()
        // The lead paragraph of the best match usually answers "what is …" on
        // its own, so it is worth one extra request.
        val key = hits.first().url.substringAfterLast("/wiki/")
        val lead =
            runCatching {
                fetch("https://en.wikipedia.org/api/rest_v1/page/summary/${URLEncoder.encode(key, "UTF-8")}")
                    ?.let { json -> JSONObject(json).optString("extract") }
            }.getOrNull().orEmpty()
        return if (lead.isBlank()) {
            hits
        } else {
            listOf(hits.first().copy(snippet = lead.take(600))) + hits.drop(1)
        }
    }

    /** Google News RSS: reliable for "who won…" and current events. */
    private fun searchNews(query: String): List<WebHit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val xml =
            fetch("https://news.google.com/rss/search?q=$encoded&hl=en-US&gl=US&ceid=US:en")
                ?: return emptyList()
        return Regex("(?is)<item>(.*?)</item>").findAll(xml)
            .take(4)
            .mapNotNull { match ->
                val item = match.groupValues[1]
                val title = tag(item, "title")
                val link = tag(item, "link")
                if (title.isBlank() || link.isBlank()) return@mapNotNull null
                WebHit(title, link, tag(item, "description").take(300))
            }.toList()
    }

    private fun tag(
        item: String,
        name: String,
    ): String {
        val raw =
            Regex("(?is)<$name>(.*?)</$name>").find(item)?.groupValues?.get(1)
                ?: return ""
        val plain =
            Regex("(?s)^\\s*<!\\[CDATA\\[(.*?)\\]\\]>\\s*$").find(raw)?.groupValues?.get(1) ?: raw
        return readableText(decodeEntities(plain))
    }

    private fun decodeEntities(text: String): String {
        val named =
            mapOf(
                "amp" to "&",
                "quot" to "\"",
                "apos" to "'",
                "lt" to "<",
                "gt" to ">",
                "nbsp" to " ",
                "ndash" to "–",
                "mdash" to "—",
                "hellip" to "…",
            )
        return Regex("&(#x?[0-9a-fA-F]{1,8}|[a-zA-Z]{2,8});").replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", true) ->
                    body.drop(2).toIntOrNull(16)?.takeIf { it in 32..0x10FFFF }?.let(::codePoint) ?: ""
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.takeIf { it in 32..0x10FFFF }?.let(::codePoint) ?: ""
                else -> named[body.lowercase()] ?: ""
            }
        }
    }

    private fun codePoint(value: Int): String =
        if (value <= 0xFFFF) value.toChar().toString() else String(Character.toChars(value))
}
