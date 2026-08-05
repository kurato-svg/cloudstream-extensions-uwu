package com.AnichinV2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnichinV2 : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin V2"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        TODO("Implement after we confirm Anichin selectors")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        TODO("Implement after we confirm Anichin search")
    }

    override suspend fun load(url: String): LoadResponse {
        TODO("Implement after we confirm Anichin series structure")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        TODO("Implement after we confirm Anichin player structure")
    }
}
