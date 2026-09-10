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

        var (subreddit, postId) = extractSubredditAndPostId(targetUrl)
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
            // 3. Fallback to combining oEmbed + Bot OpenGraph
            return resolveFallback(postId, cleanUrl, subreddit, client)
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
            displayName = "u/$authorName",
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

        val rawMap = mutableMapOf<String, String>()
        if (subreddit != null) {
            rawMap["subreddit"] = subreddit
            rawMap["board"] = subreddit
            rawMap["community"] = subreddit
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
            createdAtEpochSeconds = createdUtc,
            community = subreddit,
            rawData = rawMap
        )
    }

    private suspend fun resolveFallback(
        postId: String,
        cleanUrl: String,
        initialSubreddit: String?,
        client: SocialPeekHttpClient
    ): PeekPost {
        // 1. Fetch oEmbed (gives author_name, title, and html with subreddit)
        var authorName: String? = null
        var oembedTitle: String? = null
        var foundSubreddit: String? = initialSubreddit

        try {
            val encodedUrl = URLEncoder.encode(cleanUrl, "UTF-8")
            val oembedUrl = "https://www.reddit.com/oembed?url=$encodedUrl"
            val responseText = client.get(oembedUrl)
            val rootObj = json.parseToJsonElement(responseText).jsonObject

            authorName = rootObj["author_name"]?.jsonPrimitive?.contentOrNull
            oembedTitle = rootObj["title"]?.jsonPrimitive?.contentOrNull

            val html = rootObj["html"]?.jsonPrimitive?.contentOrNull
            if (html != null && foundSubreddit == null) {
                val subMatch = Regex("""/r/([a-zA-Z0-9_]+)/""").find(html)
                if (subMatch != null) {
                    foundSubreddit = subMatch.groupValues[1]
                }
            }
        } catch (_: Exception) {
            // Ignore oEmbed failure and continue to HTML scraping
        }

        // 2. Fetch Bot OpenGraph HTML (gives meta description with votes, comments, content, and og:image)
        var scrapedTitle: String? = null
        var scrapedContent = ""
        var scrapedVotes: Long? = null
        var scrapedComments: Long? = null
        var scrapedImage: String? = null

        try {
            val headers = mapOf(
                HttpHeaders.UserAgent to KtorSocialPeekHttpClient.BOT_USER_AGENT
            )
            val html = client.get(cleanUrl, headers)
            val doc = Jsoup.parse(html)

            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            if (!ogTitle.isNullOrBlank()) {
                val subMatch = Regex("""From the ([a-zA-Z0-9_]+) community on Reddit""", RegexOption.IGNORE_CASE).find(ogTitle)
                if (subMatch != null && foundSubreddit == null) {
                    foundSubreddit = subMatch.groupValues[1]
                }
                scrapedTitle = ogTitle.replace(Regex("""^From the \w+ community on Reddit:\s*"""), "")
            }

            val metaDesc = doc.selectFirst("meta[name=description]")?.attr("content")
            if (!metaDesc.isNullOrBlank()) {
                // Example: "197 votes, 138 comments. I'm using antigravity and this morning, my entire Google Account..."
                val descRegex = Regex("""^(?:([0-9,]+)\s*votes?,\s*)?(?:([0-9,]+)\s*comments?\.\s*)?(.*)$""", RegexOption.DOT_MATCHES_ALL)
                val descMatch = descRegex.find(metaDesc)
                if (descMatch != null) {
                    val votesStr = descMatch.groupValues[1].replace(",", "").trim()
                    val commentsStr = descMatch.groupValues[2].replace(",", "").trim()
                    val body = descMatch.groupValues[3].trim()

                    if (votesStr.isNotBlank()) scrapedVotes = votesStr.toLongOrNull()
                    if (commentsStr.isNotBlank()) scrapedComments = commentsStr.toLongOrNull()
                    scrapedContent = body
                } else {
                    scrapedContent = metaDesc
                }
            }

            scrapedImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        } catch (_: Exception) {
            // Ignore HTML scrape failure if oEmbed succeeded
        }

        val finalTitle = oembedTitle ?: scrapedTitle
        val finalAuthorName = authorName ?: "reddit_user"

        if (finalTitle.isNullOrBlank() && scrapedContent.isBlank() && authorName == null) {
            throw PostNotFoundException(cleanUrl, "Post not found or inaccessible on Reddit")
        }

        val author = Author(
            username = finalAuthorName,
            displayName = "u/$finalAuthorName",
            profileUrl = "https://www.reddit.com/user/$finalAuthorName"
        )

        val previewImageUrl = scrapedImage ?: "https://share.redd.it/preview/post/$postId"
        val mediaList = listOf(
            Media.Image(
                url = previewImageUrl,
                previewUrl = previewImageUrl
            )
        )

        val rawMap = mutableMapOf<String, String>()
        if (foundSubreddit != null) {
            rawMap["subreddit"] = foundSubreddit
            rawMap["board"] = foundSubreddit
            rawMap["community"] = foundSubreddit
        }

        return PeekPost(
            platform = Platform.REDDIT,
            id = postId,
            originalUrl = cleanUrl,
            author = author,
            title = finalTitle ?: "Reddit Post",
            content = scrapedContent,
            media = mediaList,
            metrics = Metrics(likes = scrapedVotes, comments = scrapedComments),
            community = foundSubreddit,
            rawData = rawMap
        )
    }
}
