package dev.socialpeek

import dev.socialpeek.exception.UnsupportedPlatformException
import dev.socialpeek.model.Author
import dev.socialpeek.model.Media
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SocialPeekDispatchTest {

    private val dummyResolver = object : PlatformResolver {
        override val platform = Platform.X
        override fun canResolve(url: String): Boolean = url.contains("x.com") || url.contains("twitter.com")
        override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
            return PeekPost(
                platform = Platform.X,
                id = "12345",
                originalUrl = url,
                author = Author(id = "1", username = "dummy", displayName = "Dummy User"),
                content = "Hello World",
                media = listOf(Media.Image(url = "https://example.com/img.jpg"))
            )
        }
    }

    @Test
    fun `should dispatch url to matching resolver`() = runTest {
        val client = SocialPeekClient(resolvers = listOf(dummyResolver))
        val post = client.peek("https://x.com/dummy/status/12345")
        
        assertEquals(Platform.X, post.platform)
        assertEquals("12345", post.id)
        assertEquals("Hello World", post.content)
        assertEquals("dummy", post.author.username)
        assertEquals(1, post.media.size)
    }

    @Test
    fun `should throw UnsupportedPlatformException for unregistered url in peek`() = runTest {
        val client = SocialPeekClient(resolvers = listOf(dummyResolver))
        assertFailsWith<UnsupportedPlatformException> {
            client.peek("https://unknown-platform.com/post/999")
        }
    }

    @Test
    fun `should return null for unregistered url in peekOrNull`() = runTest {
        val client = SocialPeekClient(resolvers = listOf(dummyResolver))
        val result = client.peekOrNull("https://unknown-platform.com/post/999")
        assertNull(result)
    }
}
