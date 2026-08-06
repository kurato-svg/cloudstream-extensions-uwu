package com.AnichinV2

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink

class Rumble : ExtractorApi() {

    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val response = app.get(
            url,
            referer = referer ?: "$mainUrl/"
        )

        val script = response.document
            .selectFirst("script:containsData(mp4)")
            ?.data()
            ?: return

        val regex = """"url":"(.*?)"""".toRegex()

        val visited = mutableSetOf<String>()

        regex.findAll(script)
            .map { it.groupValues[1].replace("\\/", "/") }
            .filter { it.endsWith(".m3u8") }
            .filter { visited.add(it) }
            .forEach { masterUrl ->

                generateM3u8(
                    "Rumble",
                    masterUrl,
                    referer ?: "$mainUrl/"
                )
                    .filter {
                        it.quality >= 720
                    }
                    .sortedByDescending {
                        it.quality
                    }
                    .forEach(callback)
            }
    }
}
