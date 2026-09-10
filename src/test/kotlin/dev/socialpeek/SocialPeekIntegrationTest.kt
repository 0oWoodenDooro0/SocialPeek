package dev.socialpeek

import dev.socialpeek.model.Platform
import dev.socialpeek.resolver.bilibili.BilibiliResolver
import dev.socialpeek.resolver.instagram.InstagramResolver
import dev.socialpeek.resolver.reddit.RedditResolver
import dev.socialpeek.resolver.threads.ThreadsResolver
import dev.socialpeek.resolver.x.XResolver
import dev.socialpeek.resolver.youtube.YouTubeResolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialPeekIntegrationTest {

    @Test
    fun `default client should support all 6 platforms`() {
        val client = SocialPeek.defaultClient

        assertEquals(6, client.resolvers.size)
        assertTrue(client.resolvers.any { it is BilibiliResolver })
        assertTrue(client.resolvers.any { it is XResolver })
        assertTrue(client.resolvers.any { it is InstagramResolver })
        assertTrue(client.resolvers.any { it is ThreadsResolver })
        assertTrue(client.resolvers.any { it is YouTubeResolver })
        assertTrue(client.resolvers.any { it is RedditResolver })

        // Check resolver routing
        assertEquals(Platform.BILIBILI, client.findResolver("https://www.bilibili.com/video/BV1xx411c7mD")?.platform)
        assertEquals(Platform.X, client.findResolver("https://x.com/jack/status/20")?.platform)
        assertEquals(Platform.INSTAGRAM, client.findResolver("https://www.instagram.com/p/Cx12345abc/")?.platform)
        assertEquals(Platform.THREADS, client.findResolver("https://www.threads.net/@zuck/post/CuZ12345")?.platform)
        assertEquals(Platform.YOUTUBE, client.findResolver("https://youtu.be/dQw4w9WgXcQ")?.platform)
        assertEquals(Platform.REDDIT, client.findResolver("https://redd.it/1cdefgh")?.platform)
        assertNull(client.findResolver("https://randomwebsite.com/article/1"))
    }

    @Test
    fun `SocialPeek helper methods should work`() {
        assertTrue(SocialPeek.canResolve("https://x.com/jack/status/20"))
        assertTrue(SocialPeek.canResolve("https://bilibili.com/opus/12345"))
    }
}
