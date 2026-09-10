package dev.socialpeek.resolver.instagram

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.KtorSocialPeekHttpClient
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import io.ktor.http.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class InstagramResolver : PlatformResolver {

    override val platform: Platform = Platform.INSTAGRAM

    private val instagramUrlPattern = Regex(
        """https?://(?:(?:www\.|m\.)?(?:instagram\.com|instagr\.am))/(?:share/)?(?:p|reel|tv)/([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )

    override fun canResolve(url: String): Boolean {
        return instagramUrlPattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        val match = instagramUrlPattern.find(url)
            ?: throw ParsingException(url, "Could not extract Instagram shortcode from URL")
        val shortcode = match.groupValues[1]

        val canonicalUrl = "https://www.instagram.com/p/$shortcode/"

        // 1. Primary: Try direct post page with Bot User-Agent (Meta renders full OpenGraph SSR)
        try {
            val botHeaders = mapOf(
                HttpHeaders.UserAgent to KtorSocialPeekHttpClient.BOT_USER_AGENT
            )
            val html = client.get(canonicalUrl, botHeaders)
            val doc = Jsoup.parse(html)
            val post = parseFromBotOpenGraph(shortcode, canonicalUrl, doc)
            if (post != null) return post
        } catch (e: Exception) {
            // Fallback to embed
        }

        // 2. Fallback: Embed page
        return resolveViaEmbed(shortcode, canonicalUrl, url, client)
    }

    private fun parseFromBotOpenGraph(shortcode: String, canonicalUrl: String, doc: Document): PeekPost? {
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val ogVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
        val ogDescription = doc.selectFirst("meta[property=og:description]")?.attr("content")

        if (ogTitle.isNullOrBlank() && ogImage.isNullOrBlank()) {
            return null
        }

        // Extract displayName from og:title: "<Name> on Instagram: \"<quote>\""
        var displayName = "Instagram User"
        var content = ""
        val titleMatch = Regex("""^(.*?)\s+on Instagram(?::\s*"(.*)"|\s*$)""", RegexOption.DOT_MATCHES_ALL).find(ogTitle ?: "")
        if (titleMatch != null) {
            displayName = titleMatch.groupValues[1].trim()
            content = titleMatch.groupValues[2].trim()
        }

        // Extract username and metrics from og:description: "1,891 likes, 20 comments - seleneshih on April 7, 2026: \"...\""
        var username = displayName.lowercase().replace(" ", "_")
        var likes: Long? = null
        var comments: Long? = null

        if (!ogDescription.isNullOrBlank()) {
            val likesMatch = Regex("""([\d,]+)\s+likes?""", RegexOption.IGNORE_CASE).find(ogDescription)
            val commentsMatch = Regex("""([\d,]+)\s+comments?""", RegexOption.IGNORE_CASE).find(ogDescription)
            val userMatch = Regex("""-\s+([a-zA-Z0-9_.-]+)\s+on\s+""", RegexOption.IGNORE_CASE).find(ogDescription)

            likes = likesMatch?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            comments = commentsMatch?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            userMatch?.groupValues?.get(1)?.let { username = it }

            if (content.isBlank()) {
                val quoteMatch = Regex(""":\s*"(.*)"\s*$""", RegexOption.DOT_MATCHES_ALL).find(ogDescription)
                content = quoteMatch?.groupValues?.get(1)?.trim() ?: ogDescription
            }
        }

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
            displayName = displayName,
            avatarUrl = null,
            profileUrl = "https://www.instagram.com/$username/"
        )

        return PeekPost(
            platform = Platform.INSTAGRAM,
            id = shortcode,
            originalUrl = canonicalUrl,
            author = author,
            content = content,
            media = mediaList,
            metrics = if (likes != null || comments != null) Metrics(likes = likes, comments = comments) else null
        )
    }

    private suspend fun resolveViaEmbed(
        shortcode: String,
        canonicalUrl: String,
        originalUrl: String,
        client: SocialPeekHttpClient
    ): PeekPost {
        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
        val html = try {
            client.get(embedUrl)
        } catch (e: PostNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw PostNotFoundException(originalUrl, e.message)
        }

        val doc = Jsoup.parse(html)
        val titleEl = doc.selectFirst("meta[property=og:title]")
        val descEl = doc.selectFirst("meta[property=og:description]")
        val usernameEl = doc.selectFirst(".CaptionUsername")
        val captionEl = doc.selectFirst(".CaptionComments") ?: doc.selectFirst(".Caption")

        val username = usernameEl?.text()?.trim()
            ?: extractUsernameFromOgTitle(titleEl?.attr("content"))
            ?: "instagram_user"

        val avatarUrl = doc.selectFirst(".Avatar img")?.attr("src")

        val content = captionEl?.text()?.replaceFirst(username, "")?.trim()
            ?: descEl?.attr("content")
            ?: ""

        val mediaList = mutableListOf<Media>()
        val videoEl = doc.selectFirst("video")
        val ogVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
        val videoUrl = videoEl?.attr("src")?.takeIf { it.isNotBlank() } ?: ogVideo

        if (!videoUrl.isNullOrBlank()) {
            val posterUrl = videoEl?.attr("poster")?.takeIf { it.isNotBlank() } 
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            mediaList.add(
                Media.Video(
                    url = videoUrl,
                    previewUrl = posterUrl
                )
            )
        } else {
            val imgEl = doc.selectFirst("img.EmbeddedMediaImage")
            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val imageUrl = imgEl?.attr("src")?.takeIf { it.isNotBlank() } ?: ogImage

            if (!imageUrl.isNullOrBlank()) {
                mediaList.add(
                    Media.Image(
                        url = imageUrl,
                        previewUrl = imageUrl
                    )
                )
            }
        }

        if (mediaList.isEmpty() && content.isBlank() && username == "instagram_user") {
            throw PostNotFoundException(originalUrl, "Instagram post content or media not available")
        }

        val author = Author(
            username = username,
            displayName = username,
            avatarUrl = avatarUrl,
            profileUrl = "https://www.instagram.com/$username/"
        )

        return PeekPost(
            platform = Platform.INSTAGRAM,
            id = shortcode,
            originalUrl = canonicalUrl,
            author = author,
            content = content,
            media = mediaList
        )
    }

    private fun extractUsernameFromOgTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val match = Regex("""(?:Photo|Reel|Video)?\s*(?:by\s+)?([a-zA-Z0-9_.-]+)\s+on Instagram""", RegexOption.IGNORE_CASE).find(title)
        return match?.groupValues?.get(1)
    }
}
