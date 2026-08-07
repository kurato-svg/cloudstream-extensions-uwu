package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class OppadramaProvider : MainAPI() {

    override var mainUrl = "http://45.11.57.192"
    override var name = "OppaDrama"
    override var lang = "id"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val updateUrl = "https://oppa.biz"
    private var domainChecked = false
    private var humanCookie: String? = null

    private fun siteHeaders(
        referer: String = mainUrl,
        cookie: String? = humanCookie
    ): Map<String, String> {
        val base = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
            "Referer" to referer,
            "Origin" to mainUrl
        )

        if (!cookie.isNullOrBlank()) {
            base["Cookie"] = cookie
        }

        return base
    }

    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Latest Update",
        "series/?status=&type=Drama&order=update" to "Drama",
        "series/?type=Movie&order=update" to "Movie",
        "series/?country%5B%5D=south-korea&type=Drama&order=update" to "Korea",
        "series/?country%5B%5D=china&type=Drama&order=update" to "China",
        "series/?country%5B%5D=japan&type=Drama&order=update" to "Japan",
        "series/?country%5B%5D=thailand&type=Drama&order=update" to "Thailand"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = getSiteDocument(
            buildMainPageUrl(page, request.data)
        )

        val items = document
            .select("div.listupd article.bs, div.listupd article.stylefor")
            .mapNotNull { it.toSearchResult() }

        val hasNext =
            document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            HomePageList(
                request.name,
                items
            ),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveMainUrl()

        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val document = getSiteDocument("$mainUrl/?s=$encoded")

        return document
            .select("div.listupd article.bs, div.listupd article.stylefor")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getSiteDocument(url)

        val title = document.titleText()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.poster()
        val plot = document.descriptionText()
        val tags = document.tagsList()
        val year = document.getInfo("Tahun")?.safeYear()
            ?: document.getInfo("Year")?.safeYear()

        val status = parseStatus(
            document.getInfo("Status")
                ?: document.text()
        )

        val episodes = document.episodes()

        val isMovie = url.contains("/movie-", true) ||
            document.getInfo("Tipe")?.contains("Movie", true) == true ||
            document.getInfo("Type")?.contains("Movie", true) == true ||
            episodes.isEmpty()

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.tags = tags
                plot?.let { this.plot = it }
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.year = year
                this.tags = tags
                plot?.let { this.plot = it }
                status?.let { showStatus = it }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getSiteDocument(data)
        val links = linkedSetOf<String>()

        document.select("div.player-embed iframe")
            .forEach { iframe ->
                iframe.getIframeUrl()
                    ?.toAbsoluteUrl(data)
                    ?.also { links.add(it) }
            }

        document.select("div.player-embed")
            .forEach { embed ->
                OppadramaMirrorExtractor.decodeRaw(
                    embed.html(),
                    "Main",
                    data,
                    mainUrl
                ).forEach { links.add(it) }
            }

        document.select("select.mirror option[value]")
            .forEach { option ->
                OppadramaMirrorExtractor.decodeMirror(
                    value = option.attr("value"),
                    label = option.text(),
                    referer = data,
                    mainUrl = mainUrl
                ).forEach { links.add(it) }
            }

        document.select("div.dlbox a[href]")
            .forEach { anchor ->
                anchor.attr("href")
                    .trim()
                    .takeIf { it.startsWith("http", true) }
                    ?.also { links.add(it) }
            }

        val orderedLinks = OppadramaMirrorExtractor.sortLinks(links)

        orderedLinks.forEach { link ->
            runCatching {
                loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return orderedLinks.isNotEmpty()
    }

    private suspend fun resolveMainUrl() {
        if (domainChecked) return
        domainChecked = true

        val response = runCatching {
            app.get(
                updateUrl,
                headers = siteHeaders(updateUrl, null),
                allowRedirects = false
            )
        }.getOrNull() ?: return

        val redirect = response.headers["Location"]
            ?: response.headers["location"]

        val resolved = when {
            !redirect.isNullOrBlank() -> redirect.toAbsoluteFrom(updateUrl)
            else -> response.text.findCurrentSiteUrl()
        }

        if (!resolved.isNullOrBlank()) {
            mainUrl = resolved.trimEnd('/')
            humanCookie = null
        }
    }

    private fun String.findCurrentSiteUrl(): String? {
        val text = this

        val ipUrl = Regex("""https?://(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?""")
            .find(text)
            ?.value

        if (!ipUrl.isNullOrBlank()) {
            return ipUrl
        }

        return Regex("""https?://[A-Za-z0-9.-]+""")
            .findAll(text)
            .map { it.value.trimEnd('/') }
            .firstOrNull { candidate ->
                !candidate.contains("telegram", true) &&
                    !candidate.contains("t.me", true) &&
                    !candidate.contains("google", true) &&
                    !candidate.contains("gstatic", true) &&
                    !candidate.contains("gravatar", true) &&
                    !candidate.contains("yoast", true)
            }
    }

    private suspend fun getSiteDocument(url: String): Document {
        resolveMainUrl()
        ensureHumanCookie()

        return app.get(
            url.replaceBaseIfNeeded(),
            headers = siteHeaders(),
            referer = mainUrl
        ).document
    }

    private fun String.replaceBaseIfNeeded(): String {
        val value = trim()

        if (value.startsWith(updateUrl, true)) {
            return mainUrl + value.removePrefix(updateUrl).removePrefix(updateUrl.trimEnd('/'))
        }

        return value
    }

    private suspend fun ensureHumanCookie() {
        if (!humanCookie.isNullOrBlank()) return

        val response = app.get(
            "$mainUrl/?verify_human=1",
            headers = siteHeaders(mainUrl, null),
            referer = mainUrl,
            allowRedirects = false
        )

        val setCookie = response.headers["Set-Cookie"]
            ?: response.headers["set-cookie"]

        humanCookie = setCookie
            ?.substringBefore(";")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "user_is_human=true"
    }

    private fun buildMainPageUrl(page: Int, data: String): String {
        return if (data.isBlank()) {
            if (page == 1) {
                mainUrl
            } else {
                "$mainUrl/page/$page/"
            }
        } else {
            val base = "$mainUrl/$data"

            if (page <= 1) {
                base
            } else {
                val separator = if (base.contains("?")) "&" else "?"
                "${base}${separator}page=$page"
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = selectFirst(".tt")
            ?.ownText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title")
                .trim()
                .takeIf { it.isNotBlank() }
            ?: selectFirst("h2")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = selectFirst("img")
            ?.getImageUrl()
            ?.let(::fixUrl)

        val badge = selectFirst(".typez")
            ?.text()
            ?.lowercase()
            ?: ""

        val isMovie = badge.contains("movie") ||
            href.contains("/movie-", true)

        return if (isMovie) {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }


    private fun Element.getImageUrl(): String? {
        return when {
            attr("data-src").isNotBlank() -> attr("data-src")
            attr("data-lazy-src").isNotBlank() -> attr("data-lazy-src")
            attr("data-original").isNotBlank() -> attr("data-original")
            attr("src").isNotBlank() -> attr("src")
            attr("srcset").isNotBlank() ->
                attr("srcset").substringBefore(",").substringBefore(" ")
            else -> null
        }
    }

    private fun Element.getIframeUrl(): String? {
        return when {
            attr("data-litespeed-src").isNotBlank() -> attr("data-litespeed-src")
            attr("data-src").isNotBlank() -> attr("data-src")
            attr("src").isNotBlank() -> attr("src")
            else -> null
        }
    }

    private fun String.toAbsoluteUrl(referer: String): String? {
        val value = trim()
        if (value.isBlank()) return null

        return value.toAbsoluteFrom(referer)
    }

    private fun String.toAbsoluteFrom(referer: String): String? {
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

    private fun Document.getInfo(key: String): String? {
        return select("div.spe span")
            .firstOrNull {
                it.text().startsWith("$key:", true)
            }
            ?.text()
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseStatus(text: String?): ShowStatus? {
        val value = text?.lowercase() ?: return null

        return when {
            value.contains("completed") -> ShowStatus.Completed
            value.contains("ongoing") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.safeInt(): Int? {
        return Regex("\\d+")
            .find(this)
            ?.value
            ?.toIntOrNull()
    }

    private fun String.safeYear(): Int? {
        return Regex("(19|20)\\d{2}")
            .find(this)
            ?.value
            ?.toIntOrNull()
    }

    private fun Document.episodes(): List<Episode> {
        return select("div.eplister li a")
            .reversed()
            .mapIndexed { index, element ->
                val episodeNumber = element
                    .selectFirst(".epl-num")
                    ?.text()
                    ?.safeInt()
                    ?: index + 1

                newEpisode(
                    fixUrl(element.attr("href"))
                ) {
                    episode = episodeNumber
                    name = element
                        .selectFirst(".epl-title")
                        ?.text()
                        ?.trim()
                }
            }
    }

    private fun Document.poster(): String? {
        return selectFirst(".thumb img, .poster img, .bigcontent img")
            ?.getImageUrl()
            ?.let(::fixUrl)
    }

    private fun Document.titleText(): String? {
        return selectFirst("h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.descriptionText(): String? {
        return select(".entry-content p")
            .joinToString("\n") { it.text().trim() }
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun Document.tagsList(): List<String> {
        return select(".genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
    }
}
