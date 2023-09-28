package ani.saikou.parsers.anime.extractors

import ani.saikou.FileUrl
import ani.saikou.client
import ani.saikou.getSize
import ani.saikou.parsers.Video
import ani.saikou.parsers.VideoContainer
import ani.saikou.parsers.VideoExtractor
import ani.saikou.parsers.VideoServer
import ani.saikou.parsers.VideoType

class Mp4Upload(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer {
        val link = client.get(server.embed.url).document
            .select("script").html()
            .substringAfter("src: \"").substringBefore("\"")
        val host = link.substringAfter("https://").substringBefore("/")
        val file = FileUrl(link, mapOf("host" to host))
        return VideoContainer(
            listOf(Video(null, VideoType.CONTAINER, file, getSize(file)))
        )
    }
}