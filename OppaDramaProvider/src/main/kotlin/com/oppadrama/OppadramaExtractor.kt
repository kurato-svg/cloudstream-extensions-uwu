package com.oppadrama

import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

object OppadramaMirrorExtractor {

    fun decodeMirror(
        value: String,
        label: String,
        referer: String,
        mainUrl: String
    ): List<String> {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return emptyList()

        if (cleaned.looksLikeUrl()) {
            return listOfNotNull(cleaned.toAbsoluteUrl(referer, mainUrl))
        }

        val decoded = runCatching {
            base64Decode(cleaned.replace("\\s".toRegex(), ""))
        }.getOrNull()

        return decodeRaw(
            raw = decoded ?: cleaned,
            label = label,
            referer = referer,
            mainUrl = mainUrl
        )
    }

    fun decodeRaw(
        raw: String,
        label: String,
        referer: String,
        mainUrl: String
    ): List<String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()

        val links = linkedSetOf<String>()

        if (text.looksLikeUrl()) {
            text.toAbsoluteUrl(referer, mainUrl)?.let { links.add(it) }
        }

        Jsoup.parse(text)
            .select("iframe")
            .forEach { iframe ->
                iframe.getIframeUrl()
                    ?.toAbsoluteUrl(referer, mainUrl)
                    ?.let { links.add(it) }
            }

        Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value.trim() }
            .forEach { link ->
                link.toAbsoluteUrl(referer, mainUrl)?.let { links.add(it) }
            }

        parseShortcodeLinks(text, label)
            .forEach { links.add(it) }

        return links.toList()
    }

    fun sortLinks(links: Collection<String>): List<String> {
        return links
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(
                compareBy<String> { it.hostPriority() }
                    .thenBy { it }
            )
    }

    private fun parseShortcodeLinks(
        text: String,
        label: String
    ): List<String> {
        val links = linkedSetOf<String>()

        val shortcodeRegex = Regex(
            """\[\s*([A-Za-z0-9_ -]+)\s+id\s*=\s*['"]([^'"]+)['"]\s*]""",
            RegexOption.IGNORE_CASE
        )

        shortcodeRegex.findAll(text).forEach { match ->
            val shortcodeName = match.groupValues[1].trim()
            val id = match.groupValues[2].trim()
            if (id.isBlank()) return@forEach

            val hostKey = "$label $shortcodeName".lowercase()

            when {
                hostKey.contains("streamsb") ||
                    hostKey.contains("stream sb") ||
                    hostKey.contains("sb") -> {
                    streamSbLinks(id).forEach { links.add(it) }
                }

                hostKey.contains("hydrax") ||
                    hostKey.contains("abyss") -> {
                    links.add("https://abyssplayer.com/?v=$id")
                }

                hostKey.contains("vidhide") ||
                    hostKey.contains("earnvid") -> {
                    links.add("https://vidhidepro.com/v/$id")
                    links.add("https://vidhidepro.com/d/$id")
                }

                hostKey.contains("filelion") ||
                    hostKey.contains("filelions") -> {
                    links.add("https://filelions.to/v/$id")
                }

                hostKey.contains("emturbo") ||
                    hostKey.contains("turbovip") -> {
                    links.add("https://emturbovid.com/t/$id")
                }

                hostKey.contains("gdrive") ||
                    hostKey.contains("google") -> {
                    val decodedId = id.urlDecodeTwice()
                    if (decodedId.matches(Regex("""[A-Za-z0-9_-]{20,}"""))) {
                        links.add("https://drive.google.com/file/d/$decodedId/view")
                    }
                }
            }
        }

        return links.toList()
    }

    private fun streamSbLinks(id: String): List<String> {
        val clean = id.trim()

        return listOf(
            "https://sbembed.com/embed-$clean.html",
            "https://streamsb.net/e/$clean",
            "https://watchsb.com/e/$clean",
            "https://sbplay2.com/e/$clean"
        )
    }

    private fun String.hostPriority(): Int {
        val url = lowercase()

        return when {
            url.contains("emturbovid") -> 0
            url.contains("vidhide") -> 1
            url.contains("earnvid") -> 1
            url.contains("abyssplayer") -> 2
            url.contains("hydrax") -> 2
            url.contains("streamsb") -> 3
            url.contains("sbembed") -> 3
            url.contains("watchsb") -> 3
            url.contains("sbplay") -> 3
            url.contains("minochinos") -> 4
            url.contains("filelions") -> 4
            url.contains("filelion") -> 4
            url.contains("buzzheavier") -> 20
            url.contains("filekeeper") -> 21
            url.contains("drive.google") -> 30
            else -> 10
        }
    }

    private fun String.looksLikeUrl(): Boolean {
        val value = trim()

        return value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("//") ||
            value.startsWith("/")
    }

    private fun String.toAbsoluteUrl(
        referer: String,
        mainUrl: String
    ): String? {
        val value = trim()
        if (value.isBlank()) return null

        return runCatching {
            when {
                value.startsWith("http://", true) ||
                    value.startsWith("https://", true) -> value

                value.startsWith("//") -> "https:$value"

                value.startsWith("/") -> "${mainUrl.trimEnd('/')}$value"

                else -> URI(referer).resolve(value).toString()
            }
        }.getOrNull()
    }

    private fun Element.getIframeUrl(): String? {
        return when {
            attr("data-litespeed-src").isNotBlank() ->
                attr("data-litespeed-src")

            attr("data-src").isNotBlank() ->
                attr("data-src")

            attr("src").isNotBlank() ->
                attr("src")

            else -> null
        }
    }

    private fun String.urlDecodeTwice(): String {
        return runCatching {
            URLDecoder.decode(
                URLDecoder.decode(this, Charsets.UTF_8.name()),
                Charsets.UTF_8.name()
            )
        }.getOrDefault(this)
    }
}
