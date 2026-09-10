package dev.socialpeek.resolver.instagram

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import org.jsoup.Jsoup

class InstagramResolver : PlatformResolver {

    override val platform: Platform = Platform.INSTAGRAM

    private val instagramUrlPattern = Regex(
        """https?://(?:(?:www\.|m\.)?(?:instagram\.com|instagr\.am))/(?:p|reel|tv)/([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )

    override fun canResolve(url: String): Boolean {
        return instagramUrlPattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        val match = instagramUrlPattern.find(url)
            ?: throw ParsingException(url, "Could not extract Instagram shortcode from URL")
        val shortcode = match.groupValues[1]

        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
        val html = try {
            client.get(embedUrl)
        } catch (e: PostNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw PostNotFoundException(url, e.message)
        }

        val doc = Jsoup.parse(html)

        // Check if page indicates post not found or login wall with no content
        val titleEl = doc.selectFirst("meta[property=og:title]")
        val descEl = doc.selectFirst("meta[property=og:description]")
        val usernameEl = doc.selectFirst(".CaptionUsername")
        val captionEl = doc.selectFirst(".CaptionComments") ?: doc.selectFirst(".Caption")

        val username = usernameEl?.text()?.trim()
            ?: extractUsernameFromOgTitle(titleEl?.attr("content"))
            ?: "instagram_user"

        val avatarUrl = doc.selectFirst(".Avatar img")?.attr("src")
        val canonicalUrl = "https://www.instagram.com/p/$shortcode/"

        val content = captionEl?.text()?.replaceFirst(username, "")?.trim()
            ?: descEl?.attr("content")
            ?: ""

        val mediaList = mutableListOf<Media>()

        // 1. Video check
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
            // 2. Image check
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
            throw PostNotFoundException(url, "Instagram post content or media not available")
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
        // Format: "Photo by John Doe on Instagram" or "John Doe on Instagram"
        val match = Regex("""(?:Photo|Reel|Video)?\s*(?:by\s+)?([a-zA-Z0-9_.-]+)\s+on Instagram""", RegexOption.IGNORE_CASE).find(title)
        return match?.groupValues?.get(1)
    }
}
