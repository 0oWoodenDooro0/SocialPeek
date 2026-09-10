package dev.socialpeek.resolver.util

import dev.socialpeek.model.Author
import dev.socialpeek.model.Media
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

object MetaMediaExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extracts all carousel media (images and videos) from Meta (Instagram/Threads) SSR HTML scripts.
     */
    fun extractCarouselMedia(html: String): List<Media> {
        try {
            val doc = Jsoup.parse(html)
            val scripts = doc.getElementsByTag("script")
            for (script in scripts) {
                val content = script.data().takeIf { it.isNotBlank() } ?: script.html()
                if (!content.contains("\"carousel_media\"") && !content.contains("\"edge_sidecar_to_children\"")) continue

                // 1. Try finding "carousel_media" via balanced JSON array
                if (content.contains("\"carousel_media\"")) {
                    val array = extractBalancedJsonArray(content, "carousel_media")
                    if (array != null && array.isNotEmpty()) {
                        val items = parseCarouselItems(array)
                        if (items.isNotEmpty()) return items
                    }
                }

                // 2. Try finding "edge_sidecar_to_children" (Instagram web GraphQL)
                if (content.contains("\"edge_sidecar_to_children\"")) {
                    val array = extractSidecarItems(content)
                    if (array.isNotEmpty()) return array
                }

                // 3. Fallback: Parse whole script as JSON
                try {
                    val element = json.parseToJsonElement(content)
                    val carouselArray = findKeyInJson(element, "carousel_media") as? JsonArray
                    if (carouselArray != null && carouselArray.isNotEmpty()) {
                        val items = parseCarouselItems(carouselArray)
                        if (items.isNotEmpty()) return items
                    }
                } catch (_: Exception) {
                    // Ignore parse errors
                }
            }
        } catch (_: Exception) {
            // Ignore any extraction error
        }
        return emptyList()
    }

    /**
     * Extracts author information from Meta (Instagram/Threads) SSR HTML scripts.
     */
    fun extractAuthor(html: String, fallbackUsername: String? = null): Author? {
        try {
            val doc = Jsoup.parse(html)
            val scripts = doc.getElementsByTag("script")
            for (script in scripts) {
                val content = script.data().takeIf { it.isNotBlank() } ?: script.html()
                if (!content.contains("\"profile_pic_url\"")) continue
                try {
                    val element = json.parseToJsonElement(content)
                    val userObj = findUserObject(element, fallbackUsername)
                    if (userObj != null) {
                        val username = (userObj["username"] as? JsonPrimitive)?.contentOrNull
                            ?: fallbackUsername
                            ?: "unknown"
                        val displayName = (userObj["full_name"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: username
                        val avatarUrl = (userObj["profile_pic_url"] as? JsonPrimitive)?.contentOrNull
                            ?.replace("\\/", "/")
                        val isVerified = (userObj["is_verified"] as? JsonPrimitive)?.booleanOrNull ?: false

                        return Author(
                            username = username,
                            displayName = displayName,
                            avatarUrl = avatarUrl,
                            isVerified = isVerified,
                            profileUrl = "https://www.threads.net/@$username"
                        )
                    }
                } catch (_: Exception) {
                    // Ignore
                }
            }
        } catch (_: Exception) {
            // Ignore
        }
        return null
    }

    private fun extractBalancedJsonArray(text: String, key: String): JsonArray? {
        val keyIndex = text.indexOf("\"$key\"")
        if (keyIndex == -1) return null
        val colonIndex = text.indexOf(':', keyIndex)
        if (colonIndex == -1) return null
        val startIndex = text.indexOf('[', colonIndex)
        if (startIndex == -1 || startIndex > colonIndex + 10) return null
        val slice = extractBalancedBracket(text, startIndex, '[', ']') ?: return null
        return runCatching { json.parseToJsonElement(slice) as? JsonArray }.getOrNull()
    }

    private fun extractSidecarItems(content: String): List<Media> {
        val keyIndex = content.indexOf("\"edge_sidecar_to_children\"")
        if (keyIndex == -1) return emptyList()
        val colonIndex = content.indexOf(':', keyIndex)
        if (colonIndex == -1) return emptyList()
        val startIndex = content.indexOf('{', colonIndex)
        if (startIndex == -1 || startIndex > colonIndex + 10) return emptyList()
        val slice = extractBalancedBracket(content, startIndex, '{', '}') ?: return emptyList()
        val obj = runCatching { json.parseToJsonElement(slice) as? JsonObject }.getOrNull() ?: return emptyList()
        val edges = (obj["edges"] as? JsonArray) ?: return emptyList()

        val list = mutableListOf<Media>()
        for (edge in edges) {
            val node = (edge as? JsonObject)?.get("node") as? JsonObject ?: continue
            val isVideo = (node["is_video"] as? JsonPrimitive)?.booleanOrNull ?: false
            val displayResources = node["display_resources"] as? JsonArray
            val lastResource = displayResources?.lastOrNull() as? JsonObject
            val displayUrl = (node["display_url"] as? JsonPrimitive)?.contentOrNull
                ?: (lastResource?.get("src") as? JsonPrimitive)?.contentOrNull
            val dimensions = node["dimensions"] as? JsonObject
            val width = (dimensions?.get("width") as? JsonPrimitive)?.intOrNull
            val height = (dimensions?.get("height") as? JsonPrimitive)?.intOrNull

            if (isVideo) {
                val videoUrl = (node["video_url"] as? JsonPrimitive)?.contentOrNull
                if (!videoUrl.isNullOrBlank()) {
                    list.add(Media.Video(url = videoUrl, previewUrl = displayUrl, width = width, height = height))
                    continue
                }
            }
            if (!displayUrl.isNullOrBlank()) {
                list.add(Media.Image(url = displayUrl, previewUrl = displayUrl, width = width, height = height))
            }
        }
        return list
    }

    private fun extractBalancedBracket(text: String, startIndex: Int, openChar: Char, closeChar: Char): String? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in startIndex until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == openChar) depth++
                else if (c == closeChar) {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIndex, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun findKeyInJson(element: JsonElement, targetKey: String): JsonElement? {
        if (element is JsonObject) {
            if (element.containsKey(targetKey)) {
                return element[targetKey]
            }
            for (v in element.values) {
                val found = findKeyInJson(v, targetKey)
                if (found != null) return found
            }
        } else if (element is JsonArray) {
            for (item in element) {
                val found = findKeyInJson(item, targetKey)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findUserObject(element: JsonElement, targetUsername: String?): JsonObject? {
        if (element is JsonObject) {
            if (element.containsKey("profile_pic_url") && element.containsKey("username")) {
                val u = (element["username"] as? JsonPrimitive)?.contentOrNull
                if (targetUsername == null || u.equals(targetUsername, ignoreCase = true)) {
                    return element
                }
            }
            if (element.containsKey("user") && element["user"] is JsonObject) {
                val user = element["user"] as JsonObject
                if (user.containsKey("profile_pic_url")) {
                    val u = (user["username"] as? JsonPrimitive)?.contentOrNull
                    if (targetUsername == null || u.equals(targetUsername, ignoreCase = true)) {
                        return user
                    }
                }
            }
            for (v in element.values) {
                val found = findUserObject(v, targetUsername)
                if (found != null) return found
            }
        } else if (element is JsonArray) {
            for (item in element) {
                val found = findUserObject(item, targetUsername)
                if (found != null) return found
            }
        }
        return null
    }

    private fun parseCarouselItems(carouselArray: JsonArray): List<Media> {
        val list = mutableListOf<Media>()
        for (item in carouselArray) {
            val obj = item as? JsonObject ?: continue
            val videoVersions = obj["video_versions"] as? JsonArray
            val imageVersions = (obj["image_versions2"] as? JsonObject)?.get("candidates") as? JsonArray
            val displayUri = (obj["display_uri"] as? JsonPrimitive)?.contentOrNull

            val bestCandidate = imageVersions?.firstOrNull() as? JsonObject
            val posterUrl = (bestCandidate?.get("url") as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                ?: displayUri?.replace("\\/", "/")

            if (videoVersions != null && videoVersions.isNotEmpty()) {
                val bestVideo = videoVersions.firstOrNull() as? JsonObject
                val videoUrl = (bestVideo?.get("url") as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                if (!videoUrl.isNullOrBlank()) {
                    list.add(
                        Media.Video(
                            url = videoUrl,
                            previewUrl = posterUrl,
                            width = (bestVideo["width"] as? JsonPrimitive)?.intOrNull,
                            height = (bestVideo["height"] as? JsonPrimitive)?.intOrNull
                        )
                    )
                    continue
                }
            }

            val imageUrl = (bestCandidate?.get("url") as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                ?: displayUri?.replace("\\/", "/")

            if (!imageUrl.isNullOrBlank()) {
                list.add(
                    Media.Image(
                        url = imageUrl,
                        previewUrl = imageUrl,
                        width = (bestCandidate?.get("width") as? JsonPrimitive)?.intOrNull,
                        height = (bestCandidate?.get("height") as? JsonPrimitive)?.intOrNull
                    )
                )
            }
        }
        return list
    }
}
