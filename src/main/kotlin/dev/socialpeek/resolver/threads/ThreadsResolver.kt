package dev.socialpeek.resolver.threads

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.KtorSocialPeekHttpClient
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import io.ktor.http.*
import org.jsoup.Jsoup

class ThreadsResolver : PlatformResolver {

    override val platform: Platform = Platform.THREADS

    private val threadsUrlPattern = Regex(
        """https?://(?:www\.)?threads\.(?:net|com)/(?:@([a-zA-Z0-9_.-]+)/post/|t/)([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )

    override fun canResolve(url: String): Boolean {
        return threadsUrlPattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        val match = threadsUrlPattern.find(url)
            ?: throw ParsingException(url, "Could not extract Threads post ID from URL")
        
        val urlUsername = match.groupValues[1].takeIf { it.isNotBlank() }
        val postId = match.groupValues[2]

        val targetUrl = if (urlUsername != null) {
            "https://www.threads.net/@$urlUsername/post/$postId"
        } else {
            "https://www.threads.net/t/$postId"
        }

        val headers = mapOf(
            HttpHeaders.UserAgent to KtorSocialPeekHttpClient.BOT_USER_AGENT
        )

        val html = try {
            client.get(targetUrl, headers)
        } catch (e: PostNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw PostNotFoundException(url, e.message)
        }

        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        val ogVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")

        if (title.isNullOrBlank() && desc.isBlank() && ogImage.isNullOrBlank()) {
            throw PostNotFoundException(url, "Threads post metadata not found")
        }

        val (extractedDisplayName, extractedUsername) = parseAuthorFromTitle(title)
        val finalUsername = urlUsername ?: extractedUsername ?: "threads_user"
        val displayName = extractedDisplayName ?: finalUsername

        val author = Author(
            username = finalUsername,
            displayName = displayName,
            profileUrl = "https://www.threads.net/@$finalUsername"
        )

        val mediaList = mutableListOf<Media>()
        if (!ogVideo.isNullOrBlank()) {
            mediaList.add(
                Media.Video(
                    url = ogVideo,
                    previewUrl = ogImage
                )
            )
        } else if (!ogImage.isNullOrBlank()) {
            mediaList.add(
                Media.Image(
                    url = ogImage,
                    previewUrl = ogImage
                )
            )
        }

        return PeekPost(
            platform = Platform.THREADS,
            id = postId,
            originalUrl = targetUrl,
            author = author,
            content = desc,
            media = mediaList
        )
    }

    private fun parseAuthorFromTitle(title: String?): Pair<String?, String?> {
        if (title.isNullOrBlank()) return Pair(null, null)
        // Format example: "Mark Zuckerberg (@zuck) on Threads"
        val withHandle = Regex("""^(.*?)\s*\(@([a-zA-Z0-9_.-]+)\)\s+on Threads""", RegexOption.IGNORE_CASE).find(title)
        if (withHandle != null) {
            val displayName = withHandle.groupValues[1].trim()
            val handle = withHandle.groupValues[2].trim()
            return Pair(displayName, handle)
        }

        // Format example: "@zuck on Threads"
        val onlyHandle = Regex("""^@([a-zA-Z0-9_.-]+)\s+on Threads""", RegexOption.IGNORE_CASE).find(title)
        if (onlyHandle != null) {
            val handle = onlyHandle.groupValues[1].trim()
            return Pair(handle, handle)
        }

        return Pair(null, null)
    }
}
