package dev.socialpeek.resolver.reddit

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import io.ktor.http.*
import kotlinx.serialization.json.*

class RedditResolver : PlatformResolver {

    override val platform: Platform = Platform.REDDIT

    private val redditUrlPattern = Regex(
        """https?://(?:(?:www\.|old\.|m\.)?reddit\.com/(?:r/[^/]+/)?comments/|redd\.it/)([a-zA-Z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun canResolve(url: String): Boolean {
        return redditUrlPattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        val match = redditUrlPattern.find(url)
            ?: throw ParsingException(url, "Could not extract Reddit post ID from URL")
        val postId = match.groupValues[1]

        val apiUrl = "https://www.reddit.com/comments/$postId.json?raw_json=1"
        val headers = mapOf(
            HttpHeaders.UserAgent to "SocialPeek/1.0 (Kotlin JVM Bot)"
        )

        val responseText = try {
            client.get(apiUrl, headers)
        } catch (e: Exception) {
            throw PostNotFoundException(url, e.message)
        }

        val jsonArray = try {
            json.parseToJsonElement(responseText).jsonArray
        } catch (e: Exception) {
            throw ParsingException(url, "Invalid JSON received from Reddit", e)
        }

        if (jsonArray.isEmpty()) {
            throw PostNotFoundException(url, "Empty response from Reddit")
        }

        val postListing = jsonArray[0].jsonObject["data"]?.jsonObject
        val children = postListing?.get("children")?.jsonArray ?: JsonArray(emptyList())
        if (children.isEmpty()) {
            throw PostNotFoundException(url, "No post data found in Reddit listing")
        }

        val postData = children[0].jsonObject["data"]?.jsonObject
            ?: throw ParsingException(url, "Missing post data object in Reddit response")

        val id = postData["id"]?.jsonPrimitive?.contentOrNull ?: postId
        val title = postData["title"]?.jsonPrimitive?.contentOrNull
        val content = postData["selftext"]?.jsonPrimitive?.contentOrNull ?: ""
        val authorName = postData["author"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val subreddit = postData["subreddit"]?.jsonPrimitive?.contentOrNull
        val upvotes = postData["ups"]?.jsonPrimitive?.longOrNull
        val numComments = postData["num_comments"]?.jsonPrimitive?.longOrNull
        val createdUtc = postData["created_utc"]?.jsonPrimitive?.doubleOrNull?.toLong()
        val permalink = postData["permalink"]?.jsonPrimitive?.contentOrNull

        val author = Author(
            username = authorName,
            displayName = if (subreddit != null) "r/$subreddit ($authorName)" else authorName,
            profileUrl = "https://www.reddit.com/user/$authorName"
        )

        val mediaList = mutableListOf<Media>()

        // 1. Reddit Video
        val isVideo = postData["is_video"]?.jsonPrimitive?.booleanOrNull == true
        val mediaObj = postData["media"]?.jsonObject
        val redditVideo = mediaObj?.get("reddit_video")?.jsonObject
        if (isVideo && redditVideo != null) {
            val fallbackUrl = redditVideo["fallback_url"]?.jsonPrimitive?.contentOrNull
            if (!fallbackUrl.isNullOrBlank()) {
                val duration = redditVideo["duration"]?.jsonPrimitive?.doubleOrNull
                val width = redditVideo["width"]?.jsonPrimitive?.intOrNull
                val height = redditVideo["height"]?.jsonPrimitive?.intOrNull
                val bitrateKbps = redditVideo["bitrate_kbps"]?.jsonPrimitive?.longOrNull
                val bitrate = bitrateKbps?.let { it * 1000L }
                mediaList.add(
                    Media.Video(
                        url = fallbackUrl,
                        durationSeconds = duration,
                        width = width,
                        height = height,
                        bitrate = bitrate
                    )
                )
            }
        }

        // 2. Reddit Gallery
        val isGallery = postData["is_gallery"]?.jsonPrimitive?.booleanOrNull == true
        if (isGallery) {
            val galleryItems = postData["gallery_data"]?.jsonObject?.get("items")?.jsonArray
            val mediaMetadata = postData["media_metadata"]?.jsonObject

            galleryItems?.forEach { itemElem ->
                val mediaId = itemElem.jsonObject["media_id"]?.jsonPrimitive?.contentOrNull
                if (mediaId != null && mediaMetadata != null) {
                    val meta = mediaMetadata[mediaId]?.jsonObject
                    val source = meta?.get("s")?.jsonObject
                    val sourceUrl = source?.get("u")?.jsonPrimitive?.contentOrNull?.replace("&amp;", "&")
                    val width = source?.get("x")?.jsonPrimitive?.intOrNull
                    val height = source?.get("y")?.jsonPrimitive?.intOrNull

                    // i.redd.it canonical url
                    val canonicalUrl = "https://i.redd.it/$mediaId.jpg"
                    mediaList.add(
                        Media.Image(
                            url = canonicalUrl,
                            previewUrl = sourceUrl,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        }

        // 3. Single Image
        if (mediaList.isEmpty()) {
            val postHint = postData["post_hint"]?.jsonPrimitive?.contentOrNull
            val postUrl = postData["url"]?.jsonPrimitive?.contentOrNull
            val previewImages = postData["preview"]?.jsonObject?.get("images")?.jsonArray

            if (postHint == "image" || postUrl?.matches(Regex(""".*\.(?:jpg|jpeg|png|webp|gif)""", RegexOption.IGNORE_CASE)) == true) {
                if (!postUrl.isNullOrBlank()) {
                    var width: Int? = null
                    var height: Int? = null
                    val firstPreview = previewImages?.firstOrNull()?.jsonObject?.get("source")?.jsonObject
                    if (firstPreview != null) {
                        width = firstPreview["width"]?.jsonPrimitive?.intOrNull
                        height = firstPreview["height"]?.jsonPrimitive?.intOrNull
                    }
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
            originalUrl = if (!permalink.isNullOrBlank()) "https://www.reddit.com$permalink" else url,
            author = author,
            title = title,
            content = content,
            media = mediaList,
            metrics = Metrics(likes = upvotes, comments = numComments),
            createdAtEpochSeconds = createdUtc
        )
    }
}
