package ani.saikou.parsers

import ani.saikou.Lazier
import ani.saikou.lazyList
import ani.saikou.parsers.anime.AllAnime
import ani.saikou.parsers.anime.AnimeDao
import ani.saikou.parsers.anime.AnimePahe
import ani.saikou.parsers.anime.Gogo
import ani.saikou.parsers.anime.Haho
import ani.saikou.parsers.anime.HentaiFF
import ani.saikou.parsers.anime.HentaiMama
import ani.saikou.parsers.anime.HentaiStream
import ani.saikou.parsers.anime.Marin
import ani.saikou.parsers.anime.AniWave
import ani.saikou.parsers.anime.Kaido

object AnimeSources : WatchSources() {
    override val list: List<Lazier<BaseParser>> = lazyList(
        "AllAnime" to ::AllAnime,
        "Gogo" to ::Gogo,
        "Kaido" to ::Kaido,
        "Marin" to ::Marin,
        "AnimePahe" to ::AnimePahe,
        "AniWave" to ::AniWave,
        "AnimeDao" to ::AnimeDao,
    )
}

object HAnimeSources : WatchSources() {
    private val aList: List<Lazier<BaseParser>>  = lazyList(
        "HentaiMama" to ::HentaiMama,
        "Haho" to ::Haho,
        "HentaiStream" to ::HentaiStream,
        "HentaiFF" to ::HentaiFF,
    )

    override val list = listOf(aList,AnimeSources.list).flatten()
}
