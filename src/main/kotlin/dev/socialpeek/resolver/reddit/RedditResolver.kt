package dev.socialpeek.resolver.reddit

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class RedditResolver : PlatformResolver {

    override val platform: Platform = Platform.REDDIT

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val redditCommentsPattern = Regex(
        """https?://(?:(?:www\.|old\.|new\.)?reddit\.com/(?:r/([a-zA-Z0-9_]+)/)?comments/|redd\.it/)([a-zA-Z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    private val redditSharePattern = Regex(
        """https?://(?:(?:www\.|old\.|new\.)?reddit\.com/(?:r/[a-zA-Z0-9_]+/)?s/|redd\.it/s/)([a-zA-Z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    companion object {
        val REDDIT_HEADERS = mapOf(
            HttpHeaders.UserAgent to "SocialPeek/1.0 (https://github.com/0oWoodenDooro0/SocialPeek)"
        )
    }

    override fun canResolve(url: String): Boolean {
        return redditCommentsPattern.containsMatchIn(url) || redditSharePattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        var currentUrl = url

        // Handle short share links if matched
        if (redditSharePattern.containsMatchIn(url) && !redditCommentsPattern.containsMatchIn(url)) {
            currentUrl = try {
                client.resolveFinalUrl(url, REDDIT_HEADERS)
            } catch (e: Exception) {
                url
            }
        }

        val (urlSubreddit, postId) = extractSubredditAndPostId(currentUrl)
            ?: throw ParsingException(currentUrl, "Could not extract Reddit post ID from URL: $currentUrl")

        val cleanUrl = if (urlSubreddit != null) {
            "https://www.reddit.com/r/$urlSubreddit/comments/$postId/"
        } else {
            "https://www.reddit.com/comments/$postId/"
        }

        // 1. Primary: Try direct JSON API
        val jsonUrl = "https://www.reddit.com/comments/$postId.json"
        try {
            val responseText = client.get(jsonUrl, REDDIT_HEADERS)
            return parseFromJson(responseText, postId, cleanUrl)
        } catch (e: Exception) {
            // Reddit may rate-limit (429), block, or require OAuth for JSON endpoint
        }

        // 2. Fallback: oEmbed + Bot HTML scraping
        return resolveFallback(postId, cleanUrl, urlSubreddit, client)
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

    private fun parseFromJson(jsonText: String, fallbackPostId: String, originalUrl: String): PeekPost {
        val root = try {
            json.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw ParsingException(originalUrl, "Failed to parse Reddit JSON response", e)
        }

        val jsonArray = root as? JsonArray
            ?: throw PostNotFoundException(originalUrl, "Unexpected Reddit API format")

        if (jsonArray.isEmpty()) {
            throw PostNotFoundException(originalUrl, "Empty response from Reddit API")
        }

        val listing = jsonArray[0].jsonObject
        val children = listing["data"]?.jsonObject?.get("children")?.jsonArray
        if (children.isNullOrEmpty()) {
            throw PostNotFoundException(originalUrl, "Post not found in Reddit listing")
        }

        val postData = children[0].jsonObject["data"]?.jsonObject
            ?: throw PostNotFoundException(originalUrl, "Post data missing")

        val id = postData["id"]?.jsonPrimitive?.contentOrNull ?: fallbackPostId
        val authorName = postData["author"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val authorFullname = postData["author_fullname"]?.jsonPrimitive?.contentOrNull
        val title = postData["title"]?.jsonPrimitive?.contentOrNull ?: "Reddit Post"
        val selftext = postData["selftext"]?.jsonPrimitive?.contentOrNull ?: ""
        val subreddit = postData["subreddit"]?.jsonPrimitive?.contentOrNull
        val ups = postData["ups"]?.jsonPrimitive?.longOrNull
        val numComments = postData["num_comments"]?.jsonPrimitive?.longOrNull
        val createdUtc = postData["created_utc"]?.jsonPrimitive?.doubleOrNull?.toLong()
        val permalink = postData["permalink"]?.jsonPrimitive?.contentOrNull

        val author = Author(
            id = authorFullname,
            username = authorName,
            displayName = "u/$authorName",
            profileUrl = "https://www.reddit.com/user/$authorName"
        )

        val mediaList = mutableListOf<Media>()

        // Check for video (Reddit video or rich:video)
        val isVideo = postData["is_video"]?.jsonPrimitive?.booleanOrNull ?: false
        val redditVideo = postData["media"]?.jsonObject?.get("reddit_video")?.jsonObject
        val secureMediaVideo = postData["secure_media"]?.jsonObject?.get("reddit_video")?.jsonObject
        val videoObj = redditVideo ?: secureMediaVideo

        if (isVideo && videoObj != null) {
            val fallbackUrl = videoObj["fallback_url"]?.jsonPrimitive?.contentOrNull
            val hlsUrl = videoObj["hls_url"]?.jsonPrimitive?.contentOrNull
            val duration = videoObj["duration"]?.jsonPrimitive?.doubleOrNull
            val width = videoObj["width"]?.jsonPrimitive?.intOrNull
            val height = videoObj["height"]?.jsonPrimitive?.intOrNull

            val previewImages = postData["preview"]?.jsonObject?.get("images")?.jsonArray
            val previewSource = previewImages?.firstOrNull()?.jsonObject?.get("source")?.jsonObject
            val previewUrl = previewSource?.get("url")?.jsonPrimitive?.contentOrNull?.replace("&amp;", "&")

            val videoUrl = fallbackUrl ?: hlsUrl
            if (!videoUrl.isNullOrBlank()) {
                mediaList.add(
                    Media.Video(
                        url = videoUrl,
                        previewUrl = previewUrl,
                        durationSeconds = duration,
                        width = width,
                        height = height
                    )
                )
            }
        } else {
            // Check for gallery / multi-image
            val isGallery = postData["is_gallery"]?.jsonPrimitive?.booleanOrNull ?: false
            val galleryData = postData["gallery_data"]?.jsonObject?.get("items")?.jsonArray
            val mediaMetadata = postData["media_metadata"]?.jsonObject

            if (mediaMetadata != null && (isGallery || galleryData != null || mediaMetadata.isNotEmpty())) {
                val mediaIds = galleryData?.mapNotNull { it.jsonObject["media_id"]?.jsonPrimitive?.contentOrNull }
                    ?: mediaMetadata.keys.toList()

                mediaIds.forEach { mediaId ->
                    val meta = mediaMetadata[mediaId]?.jsonObject
                    val s = meta?.get("s")?.jsonObject
                    val imgUrl = s?.get("u")?.jsonPrimitive?.contentOrNull?.replace("&amp;", "&")
                        ?: s?.get("gif")?.jsonPrimitive?.contentOrNull?.replace("&amp;", "&")
                    val width = s?.get("x")?.jsonPrimitive?.intOrNull
                    val height = s?.get("y")?.jsonPrimitive?.intOrNull

                    if (!imgUrl.isNullOrBlank() && !isPlatformPreviewOrAsset(imgUrl)) {
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

            // If no gallery images found, check single image
            if (mediaList.isEmpty()) {
                val postUrl = postData["url"]?.jsonPrimitive?.contentOrNull
                val previewImages = postData["preview"]?.jsonObject?.get("images")?.jsonArray
                val firstPreview = previewImages?.firstOrNull()?.jsonObject?.get("source")?.jsonObject

                if (postUrl != null && !isPlatformPreviewOrAsset(postUrl) && (
                        postUrl.contains("i.redd.it") ||
                        postUrl.contains("preview.redd.it") ||
                        postUrl.endsWith(".jpg") || postUrl.endsWith(".jpeg") ||
                        postUrl.endsWith(".png") || postUrl.endsWith(".webp")
                    )) {
                    val width = firstPreview?.get("width")?.jsonPrimitive?.intOrNull
                    val height = firstPreview?.get("height")?.jsonPrimitive?.intOrNull
                    mediaList.add(
                        Media.Image(
                            url = postUrl,
                            previewUrl = firstPreview?.get("url")?.jsonPrimitive?.contentOrNull?.replace("&amp;", "&") ?: postUrl,
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

        val originalPostUrl = if (!permalink.isNullOrBlank()) {
            "https://www.reddit.com$permalink"
        } else {
            originalUrl
        }

        return PeekPost(
            platform = Platform.REDDIT,
            id = id,
            originalUrl = originalPostUrl,
            author = author,
            title = title,
            content = selftext,
            media = mediaList,
            metrics = if (ups != null || numComments != null) Metrics(likes = ups, comments = numComments) else null,
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
        // 1. Try oEmbed
        var oembedTitle: String? = null
        var authorName: String? = null
        var foundSubreddit: String? = initialSubreddit
        val oembedUrl = "https://www.reddit.com/oembed?url=$cleanUrl"
        try {
            val oembedText = client.get(oembedUrl, REDDIT_HEADERS)
            val oembedJson = json.parseToJsonElement(oembedText).jsonObject
            oembedTitle = oembedJson["title"]?.jsonPrimitive?.contentOrNull
            authorName = oembedJson["author_name"]?.jsonPrimitive?.contentOrNull

            val html = oembedJson["html"]?.jsonPrimitive?.contentOrNull
            if (html != null && foundSubreddit == null) {
                val subMatch = Regex("""/r/([a-zA-Z0-9_]+)/""").find(html)
                if (subMatch != null) {
                    foundSubreddit = subMatch.groupValues[1]
                }
            }
        } catch (_: Exception) {
            // Ignore oEmbed failure
        }

        // 2. Try HTML scraping
        var scrapedTitle: String? = null
        var scrapedContent: String = ""
        var scrapedVotes: Long? = null
        var scrapedComments: Long? = null
        val scrapedImages = mutableListOf<String>()
        var scrapedVideo: String? = null

        try {
            val html = client.get(cleanUrl, REDDIT_HEADERS)
            val doc = Jsoup.parse(html)

            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            if (!ogTitle.isNullOrBlank()) {
                val subMatch = Regex("""From the ([a-zA-Z0-9_]+) community on Reddit""", RegexOption.IGNORE_CASE).find(ogTitle)
                if (subMatch != null && foundSubreddit == null) {
                    foundSubreddit = subMatch.groupValues[1]
                }
                scrapedTitle = ogTitle.replace(Regex("""^From the \w+ community on Reddit:\s*"""), "")
            } else {
                scrapedTitle = doc.selectFirst("title")?.text()
            }

            // Subreddit extraction fallback
            if (foundSubreddit == null) {
                val subEl = doc.selectFirst("meta[name=twitter:creator]") ?: doc.selectFirst("a[href^=/r/]")
                val subText = subEl?.attr("content") ?: subEl?.attr("href") ?: ""
                val subMatch = Regex("""/r/([a-zA-Z0-9_]+)""").find(subText)
                if (subMatch != null) {
                    foundSubreddit = subMatch.groupValues[1]
                }
            }

            val metaDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            if (!metaDesc.isNullOrBlank()) {
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

            val ogImages = doc.select("meta[property=og:image]")
                .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() && !isPlatformPreviewOrAsset(c) } }
            scrapedImages.addAll(ogImages)

            scrapedVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video:url]")?.attr("content")
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

        val mediaList = mutableListOf<Media>()
        if (!scrapedVideo.isNullOrBlank()) {
            mediaList.add(
                Media.Video(
                    url = scrapedVideo,
                    previewUrl = scrapedImages.firstOrNull()
                )
            )
        } else {
            scrapedImages.distinct().forEach { imgUrl ->
                mediaList.add(
                    Media.Image(
                        url = imgUrl,
                        previewUrl = imgUrl
                    )
                )
            }
        }

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

    private fun isPlatformPreviewOrAsset(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("share.redd.it/preview/post/") ||
                lower.contains("redditstatic.com") ||
                lower.contains("redditinc.com") ||
                lower.contains("/shreddit/assets/") ||
                lower.contains("styles/communityicon") ||
                lower.contains("styles/bannerbackgroundimage") ||
                lower.contains("styles/profileicon")
    }
}
