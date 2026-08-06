package com.AnichinV2

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

class OkRuSSL : Odnoklassniki() {
    override var name = "OkRuSSL"
    override var mainUrl = "https://ok.ru"
}

class OkRuHTTP : Odnoklassniki() {
    override var name = "OkRuHTTP"
    override var mainUrl = "http://ok.ru"
}

open class Odnoklassniki : ExtractorApi() {

    override val name = "Odnoklassniki"
    override val mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT
        )

        val embedUrl = url.replace("/video/", "/videoembed/")

        val response = app.get(
            embedUrl,
            headers = headers
        ).text
            .replace("\\&quot;", "\"")
            .replace("\\\\", "\\")
            .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) {
                Integer.parseInt(it.groupValues[1], 16).toChar().toString()
            }

        val videosString = Regex(""""videos":(\[[^]]*])""")
            .find(response)
            ?.groupValues
            ?.get(1)
            ?: throw ErrorLoadingException("Videos not found")

        val videos = AppUtils.tryParseJson<List<OkRuVideo>>(videosString)
            ?: throw ErrorLoadingException("Videos not found")

        val visited = mutableSetOf<String>()

        videos
            .sortedByDescending {

                val quality = it.name.uppercase()
                    .replace("MOBILE", "144p")
                    .replace("LOWEST", "240p")
                    .replace("LOW", "360p")
                    .replace("SD", "480p")
                    .replace("HD", "720p")
                    .replace("FULL", "1080p")
                    .replace("QUAD", "1440p")
                    .replace("ULTRA", "2160p")

                getQualityFromName(quality)
            }
            .forEach { video ->

                val videoUrl =
                    if (video.url.startsWith("//"))
                        "https:${video.url}"
                    else
                        video.url

                if (!visited.add(videoUrl))
                    return@forEach

                val qualityName = video.name.uppercase()
                    .replace("MOBILE", "144p")
                    .replace("LOWEST", "240p")
                    .replace("LOW", "360p")
                    .replace("SD", "480p")
                    .replace("HD", "720p")
                    .replace("FULL", "1080p")
                    .replace("QUAD", "1440p")
                    .replace("ULTRA", "2160p")

                val quality = getQualityFromName(qualityName)

                if (quality < 720)
                    return@forEach

                callback(
                    newExtractorLink(
                        source = "OkRu",
                        name = "OkRu",
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.quality = quality
                        this.referer = "$mainUrl/"
                        this.headers = headers
                    }
                )
            }
    }

    data class OkRuVideo(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("url")
        val url: String
    )
}
