package dev.socialpeek.resolver.threads

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.htmlResponse
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadsResolverTest {

    private val resolver = ThreadsResolver()

    @Test
    fun `canResolve should match threads post and share URLs`() {
        assertTrue(resolver.canResolve("https://www.threads.net/@zuck/post/CuZ12345/"))
        assertTrue(resolver.canResolve("https://threads.net/@developer/post/CuZ12345"))
        assertTrue(resolver.canResolve("https://www.threads.net/t/CuZ12345/"))
        assertTrue(resolver.canResolve("https://www.threads.com/share/BAENHoOpq1/"))
        assertTrue(resolver.canResolve("https://threads.net/share/BAENHoOpq1"))
        assertFalse(resolver.canResolve("https://www.threads.net/@zuck"))
        assertFalse(resolver.canResolve("https://twitter.com/jack/status/20"))
    }

    @Test
    fun `resolve should parse image post from threads page`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Mark Zuckerberg (@zuck) on Threads" />
            <meta property="og:description" content="10 million sign ups in seven hours 🤯" />
            <meta property="og:image" content="https://scontent.cdninstagram.com/threads_photo.jpg" />
            <meta property="og:url" content="https://www.threads.net/@zuck/post/CuZ12345" />
        </head>
        <body>
            <div id="mount_0_0"></div>
        </body>
        </html>
        """.trimIndent()

        val client = createMockHttpClient { request ->
            assertTrue(request.url.host.contains("threads.net"))
            htmlResponse(pageHtml)
        }

        val post = resolver.resolve("https://www.threads.net/@zuck/post/CuZ12345/", client)

        assertEquals(Platform.THREADS, post.platform)
        assertEquals("CuZ12345", post.id)
        assertEquals("zuck", post.author.username)
        assertEquals("Mark Zuckerberg", post.author.displayName)
        assertEquals("https://www.threads.net/@zuck", post.author.profileUrl)
        assertEquals("10 million sign ups in seven hours 🤯", post.content)
        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://scontent.cdninstagram.com/threads_photo.jpg", image.url)
    }

    @Test
    fun `resolve should handle threads share link redirect`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Snow Island (@snowisland.jp) on Threads" />
            <meta property="og:description" content="Share link content" />
            <meta property="og:image" content="https://scontent.cdninstagram.com/share_photo.jpg" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath.contains("/share/")) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://www.threads.net/@snowisland.jp/post/DdCByQeAS92")
                )
            } else {
                htmlResponse(pageHtml)
            }
        }

        val post = resolver.resolve("https://www.threads.com/share/BAENHoOpq1/", client)
        assertEquals("DdCByQeAS92", post.id)
        assertEquals("snowisland.jp", post.author.username)
    }

    @Test
    fun `resolve should parse video post from threads page`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Tech Insider (@techinsider) on Threads" />
            <meta property="og:description" content="Watch this amazing video!" />
            <meta property="og:image" content="https://scontent.cdninstagram.com/preview.jpg" />
            <meta property="og:video" content="https://scontent.cdninstagram.com/video.mp4" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        val post = resolver.resolve("https://www.threads.net/@techinsider/post/CuZVideo123", client)

        assertEquals("CuZVideo123", post.id)
        assertEquals(1, post.media.size)
        val video = post.media.first() as Media.Video
        assertEquals("https://scontent.cdninstagram.com/video.mp4", video.url)
        assertEquals("https://scontent.cdninstagram.com/preview.jpg", video.previewUrl)
    }

    @Test
    fun `resolve should throw PostNotFoundException when threads page returns 404`() = runTest {
        val client = createMockHttpClient {
            htmlResponse("Not found", HttpStatusCode.NotFound)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://www.threads.net/@nobody/post/NotExist123", client)
        }
    }
}
