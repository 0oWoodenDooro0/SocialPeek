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

    private val threadsSharePattern = Regex(
        """https?://(?:www\.)?threads\.(?:net|com)/share/([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )

    override fun canResolve(url: String): Boolean {
        return threadsUrlPattern.containsMatchIn(url) || threadsSharePattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        var currentUrl = url
        if (threadsSharePattern.containsMatchIn(url)) {
            currentUrl = try {
                client.resolveFinalUrl(url, mapOf(HttpHeaders.UserAgent to KtorSocialPeekHttpClient.BOT_USER_AGENT))
            } catch (e: Exception) {
                url
            }
        }

        val match = threadsUrlPattern.find(currentUrl)
            ?: throw ParsingException(currentUrl, "Could not extract Threads post ID from URL: $currentUrl")
        
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

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val ogDescription = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val ogVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")

        if (ogTitle.isNullOrBlank() && ogDescription.isNullOrBlank() && ogImage.isNullOrBlank()) {
            throw PostNotFoundException(url, "Threads post not found or empty response")
        }

        val (username, displayName) = extractUser(ogTitle, urlUsername)

        val content = ogDescription ?: ""
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

        val author = Author(
            username = username,
            displayName = displayName ?: username,
            avatarUrl = null,
            profileUrl = "https://www.threads.net/@$username"
        )

        return PeekPost(
            platform = Platform.THREADS,
            id = postId,
            originalUrl = "https://www.threads.net/@$username/post/$postId",
            author = author,
            content = content,
            media = mediaList
        )
    }

    private fun extractUser(ogTitle: String?, fallbackUsername: String?): Pair<String, String?> {
        if (ogTitle.isNullOrBlank()) {
            val u = fallbackUsername ?: "threads_user"
            return u to u
        }
        val match = Regex("""^(.*?)\s*\(@([a-zA-Z0-9_.-]+)\)\s*on Threads""", RegexOption.IGNORE_CASE).find(ogTitle)
        return if (match != null) {
            val name = match.groupValues[1].trim()
            val handle = match.groupValues[2].trim()
            handle to (name.ifBlank { handle })
        } else {
            val u = fallbackUsername ?: "threads_user"
            u to u
        }
    }
}
