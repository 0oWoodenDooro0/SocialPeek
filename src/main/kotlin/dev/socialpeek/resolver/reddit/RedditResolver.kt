package dev.socialpeek.resolver.reddit

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.KtorSocialPeekHttpClient
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class RedditResolver : PlatformResolver {

    override val platform: Platform = Platform.REDDIT

    private val redditCommentsPattern = Regex(
        """https?://(?:(?:www\.|old\.|new\.)?reddit\.com/(?:r/([a-zA-Z0-9_]+)/)?comments/|redd\.it/)([a-zA-Z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    private val redditSharePattern = Regex(
        """https?://(?:(?:www\.|old\.|new\.)?reddit\.com/(?:r/[a-zA-Z0-9_]+/)?s/)([a-zA-Z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun canResolve(url: String): Boolean {
        return redditCommentsPattern.containsMatchIn(url) || redditSharePattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        var targetUrl = url
        if (redditSharePattern.containsMatchIn(url) && !redditCommentsPattern.containsMatchIn(url)) {
            targetUrl = try {
                client.resolveFinalUrl(url)
            } catch (e: Exception) {
                url
            }
        }

        val (subreddit, postId) = extractSubredditAndPostId(targetUrl)
            ?: throw ParsingException(targetUrl, "Could not extract Reddit post ID from URL: $targetUrl")

        // 1. Clean canonical URL (without tracking query params)
        val cleanUrl = if (subreddit != null) {
            "https://www.reddit.com/r/$subreddit/comments/$postId/"
        } else {
            "https://www.reddit.com/comments/$postId/"
        }

        // 2. Try JSON API first
        val jsonApiUrl = "https://www.reddit.com/comments/$postId.json"
        try {
            val responseText = client.get(jsonApiUrl)
            return parseFromJson(responseText, cleanUrl)
        } catch (e: Exception) {
            // 3. Fallback to oEmbed with full cleanUrl
            try {
                return resolveViaOEmbed(postId, cleanUrl, client)
            } catch (e2: Exception) {
                // 4. Fallback to Bot OpenGraph HTML
                return resolveViaOpenGraph(postId, cleanUrl, client)
            }
        }
    }

    private fun extractSubredditAndPostId(url: String): Pair<String?, String>? {
        val commentsMatch = redditCommentsPattern.find(url)
        if (commentsMatch != null) {
            val sub = commentsMatch.groupValues[1].takeIf { it.isNotBlank() }
            val id = commentsMatch.groupValues[2]
            return sub to id
        }
        val reddItMatch = Regex("""https?://redd\.it/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE).find(url)
        if (reddItMatch != null) {
            return null to reddItMatch.groupValues[1]
        }
        val shareMatch = redditSharePattern.find(url)
        if (shareMatch != null) {
            return null to shareMatch.groupValues[1]
        }
        return null
    }

    private fun parseFromJson(responseText: String, originalUrl: String): PeekPost {
        val rootArray = try {
            json.parseToJsonElement(responseText).jsonArray
        } catch (e: Exception) {
            throw ParsingException(originalUrl, "Invalid JSON received from Reddit", e)
        }

        if (rootArray.isEmpty()) {
            throw PostNotFoundException(originalUrl, "Empty response from Reddit API")
        }

        val postListing = rootArray[0].jsonObject
        val children = postListing["data"]?.jsonObject?.get("children")?.jsonArray
        if (children.isNullOrEmpty()) {
            throw PostNotFoundException(originalUrl, "No post data found in Reddit response")
        }

        val postData = children[0].jsonObject["data"]?.jsonObject
            ?: throw ParsingException(originalUrl, "Missing post data object")

        val id = postData["id"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val title = postData["title"]?.jsonPrimitive?.contentOrNull
        val content = postData["selftext"]?.jsonPrimitive?.contentOrNull ?: ""
        val authorName = postData["author"]?.jsonPrimitive?.contentOrNull ?: "[deleted]"
        val subreddit = postData["subreddit"]?.jsonPrimitive?.contentOrNull
        val upvotes = postData["ups"]?.jsonPrimitive?.longOrNull
        val numComments = postData["num_comments"]?.jsonPrimitive?.longOrNull
        val permalink = postData["permalink"]?.jsonPrimitive?.contentOrNull
        val createdUtc = postData["created_utc"]?.jsonPrimitive?.doubleOrNull?.toLong()

        val author = Author(
            username = authorName,
            displayName = if (subreddit != null) "r/$subreddit" else authorName,
            profileUrl = "https://www.reddit.com/user/$authorName"
        )

        val mediaList = mutableListOf<Media>()

        // Check for reddit native video
        val isVideo = postData["is_video"]?.jsonPrimitive?.booleanOrNull ?: false
        val mediaObj = postData["media"]?.jsonObject
        val redditVideo = mediaObj?.get("reddit_video")?.jsonObject

        if (isVideo && redditVideo != null) {
            val fallbackUrl = redditVideo["fallback_url"]?.jsonPrimitive?.contentOrNull
            val duration = redditVideo["duration"]?.jsonPrimitive?.doubleOrNull
            val previewImages = postData["preview"]?.jsonObject?.get("images")?.jsonArray
            val previewUrl = previewImages?.firstOrNull()?.jsonObject
                ?.get("source")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?.replace("&amp;", "&")

            if (!fallbackUrl.isNullOrBlank()) {
                mediaList.add(
                    Media.Video(
                        url = fallbackUrl,
                        previewUrl = previewUrl,
                        durationSeconds = duration
                    )
                )
            }
        } else {
            // Check for gallery
            val isGallery = postData["is_gallery"]?.jsonPrimitive?.booleanOrNull ?: false
            val galleryData = postData["gallery_data"]?.jsonObject?.get("items")?.jsonArray
            val mediaMetadata = postData["media_metadata"]?.jsonObject

            if (isGallery && galleryData != null && mediaMetadata != null) {
                galleryData.forEach { itemElem ->
                    val mediaId = itemElem.jsonObject["media_id"]?.jsonPrimitive?.contentOrNull
                    if (mediaId != null) {
                        val meta = mediaMetadata[mediaId]?.jsonObject
                        val s = meta?.get("s")?.jsonObject
                        val imgUrl = s?.get("u")?.jsonPrimitive?.contentOrNull?.replace("&amp;", "&")
                        val width = s?.get("x")?.jsonPrimitive?.intOrNull
                        val height = s?.get("y")?.jsonPrimitive?.intOrNull

                        if (!imgUrl.isNullOrBlank()) {
                            mediaList.add(
                                Media.Image(
                                    url = imgUrl,
                                    previewUrl = imgUrl,
                                    width = width,
                                    height = height
                                )
                            )
                        }
                    }
                }
            } else {
                // Check single image
                val postUrl = postData["url"]?.jsonPrimitive?.contentOrNull
                val previewImages = postData["preview"]?.jsonObject?.get("images")?.jsonArray
                val firstPreview = previewImages?.firstOrNull()?.jsonObject?.get("source")?.jsonObject

                if (postUrl != null && (postUrl.endsWith(".jpg") || postUrl.endsWith(".jpeg") || postUrl.endsWith(".png") || postUrl.endsWith(".webp"))) {
                    val width = firstPreview?.get("width")?.jsonPrimitive?.intOrNull
                    val height = firstPreview?.get("height")?.jsonPrimitive?.intOrNull
                    mediaList.add(
                        Media.Image(
                            url = postUrl,
                            previewUrl = firstPreview?.get("url")?.jsonPrimitive?.contentOrNull?.replace("&amp;", "&"),
                            width = width,
                            height = height
                        )
                    )
                }
            }
        }

        return PeekPost(
            platform = Platform.REDDIT,
            id = id,
            originalUrl = if (!permalink.isNullOrBlank()) "https://www.reddit.com$permalink" else originalUrl,
            author = author,
            title = title,
            content = content,
            media = mediaList,
            metrics = Metrics(likes = upvotes, comments = numComments),
            createdAtEpochSeconds = createdUtc
        )
    }

    private suspend fun resolveViaOEmbed(postId: String, cleanUrl: String, client: SocialPeekHttpClient): PeekPost {
        val encodedUrl = URLEncoder.encode(cleanUrl, "UTF-8")
        val oembedUrl = "https://www.reddit.com/oembed?url=$encodedUrl"
        val responseText = client.get(oembedUrl)

        val rootObj = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            throw PostNotFoundException(cleanUrl, "Invalid oEmbed JSON")
        }

        val title = rootObj["title"]?.jsonPrimitive?.contentOrNull ?: "Reddit Post"
        val authorName = rootObj["author_name"]?.jsonPrimitive?.contentOrNull ?: "reddit_user"

        val author = Author(
            username = authorName,
            displayName = authorName,
            profileUrl = "https://www.reddit.com/user/$authorName"
        )

        val previewImageUrl = "https://share.redd.it/preview/post/$postId"
        val mediaList = listOf(
            Media.Image(
                url = previewImageUrl,
                previewUrl = previewImageUrl
            )
        )

        return PeekPost(
            platform = Platform.REDDIT,
            id = postId,
            originalUrl = cleanUrl,
            author = author,
            title = title,
            content = "",
            media = mediaList
        )
    }

    private suspend fun resolveViaOpenGraph(postId: String, cleanUrl: String, client: SocialPeekHttpClient): PeekPost {
        val headers = mapOf(
            HttpHeaders.UserAgent to KtorSocialPeekHttpClient.BOT_USER_AGENT
        )
        val html = try {
            client.get(cleanUrl, headers)
        } catch (e: Exception) {
            throw PostNotFoundException(cleanUrl, "Post not found on Reddit: ${e.message}")
        }

        val doc = Jsoup.parse(html)

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")

        if (ogTitle.isNullOrBlank() && ogDesc.isNullOrBlank()) {
            throw PostNotFoundException(cleanUrl, "Reddit post not found or empty response")
        }

        val title = if (!ogTitle.isNullOrBlank()) {
            ogTitle.replace(Regex("""^From the \w+ community on Reddit:\s*"""), "")
        } else "Reddit Post"

        val author = Author(
            username = "reddit_user",
            displayName = "Reddit",
            profileUrl = cleanUrl
        )

        val previewImage = ogImage ?: "https://share.redd.it/preview/post/$postId"
        val mediaList = listOf(
            Media.Image(
                url = previewImage,
                previewUrl = previewImage
            )
        )

        return PeekPost(
            platform = Platform.REDDIT,
            id = postId,
            originalUrl = cleanUrl,
            author = author,
            title = title,
            content = ogDesc ?: "",
            media = mediaList
        )
    }
}
