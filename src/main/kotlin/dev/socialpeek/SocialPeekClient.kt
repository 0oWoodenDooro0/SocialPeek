package dev.socialpeek

import dev.socialpeek.exception.UnsupportedPlatformException
import dev.socialpeek.model.PeekPost
import dev.socialpeek.network.KtorSocialPeekHttpClient
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.resolver.bilibili.BilibiliResolver
import dev.socialpeek.resolver.instagram.InstagramResolver
import dev.socialpeek.resolver.reddit.RedditResolver
import dev.socialpeek.resolver.threads.ThreadsResolver
import dev.socialpeek.resolver.x.XResolver
import dev.socialpeek.resolver.youtube.YouTubeResolver

class SocialPeekClient(
    val resolvers: List<PlatformResolver> = defaultResolvers(),
    val httpClient: SocialPeekHttpClient = KtorSocialPeekHttpClient()
) {

    companion object {
        fun defaultResolvers(): List<PlatformResolver> = listOf(
            BilibiliResolver(),
            XResolver(),
            InstagramResolver(),
            ThreadsResolver(),
            YouTubeResolver(),
            RedditResolver()
        )
    }

    /**
     * Finds the first resolver matching the given [url] and extracts the structured [PeekPost].
     * Throws [UnsupportedPlatformException] if no resolver supports the URL.
     */
    suspend fun peek(url: String): PeekPost {
        val resolver = findResolver(url) ?: throw UnsupportedPlatformException(url)
        return resolver.resolve(url, httpClient)
    }

    /**
     * Resolves the [url], or returns null if no resolver matches or if resolving fails with an exception.
     */
    suspend fun peekOrNull(url: String): PeekPost? {
        val resolver = findResolver(url) ?: return null
        return try {
            resolver.resolve(url, httpClient)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks if any registered resolver can handle this [url].
     */
    fun canResolve(url: String): Boolean {
        return findResolver(url) != null
    }

    /**
     * Finds a matching resolver for the given [url], or null if none match.
     */
    fun findResolver(url: String): PlatformResolver? {
        return resolvers.firstOrNull { it.canResolve(url) }
    }
}
