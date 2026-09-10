package dev.socialpeek.resolver.instagram

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.htmlResponse
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstagramResolverTest {

    private val resolver = InstagramResolver()

    @Test
    fun `canResolve should match instagram post, reel, and tv URLs`() {
        assertTrue(resolver.canResolve("https://www.instagram.com/p/Cx12345abc/"))
        assertTrue(resolver.canResolve("https://instagram.com/reel/Cx12345abc"))
        assertTrue(resolver.canResolve("https://instagr.am/p/Cx12345abc/"))
        assertTrue(resolver.canResolve("https://www.instagram.com/tv/Cx12345abc/"))
        assertFalse(resolver.canResolve("https://www.instagram.com/stories/user/123/"))
        assertFalse(resolver.canResolve("https://twitter.com/jack/status/20"))
    }

    @Test
    fun `resolve should parse instagram image post from embed page`() = runTest {
        val embedHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Photo by John Doe on Instagram" />
            <meta property="og:description" content="Beautiful sunset in Tokyo! #travel #sunset" />
            <meta property="og:image" content="https://instagram.fxxx.fbcdn.net/sunset.jpg" />
        </head>
        <body>
            <div class="Caption">
                <a class="CaptionUsername" href="/johndoe/">johndoe</a>
                <span class="CaptionComments">Beautiful sunset in Tokyo! #travel #sunset</span>
            </div>
            <div class="Avatar">
                <img src="https://instagram.fxxx.fbcdn.net/avatar.jpg" alt="johndoe's profile picture" />
            </div>
            <img class="EmbeddedMediaImage" src="https://instagram.fxxx.fbcdn.net/sunset.jpg" />
        </body>
        </html>
        """.trimIndent()

        val client = createMockHttpClient { request ->
            assertTrue(request.url.encodedPath.contains("embed/captioned"))
            htmlResponse(embedHtml)
        }

        val post = resolver.resolve("https://www.instagram.com/p/Cx12345abc/", client)

        assertEquals(Platform.INSTAGRAM, post.platform)
        assertEquals("Cx12345abc", post.id)
        assertEquals("johndoe", post.author.username)
        assertEquals("https://www.instagram.com/johndoe/", post.author.profileUrl)
        assertEquals("https://instagram.fxxx.fbcdn.net/avatar.jpg", post.author.avatarUrl)
        assertTrue(post.content.contains("Beautiful sunset in Tokyo!"))
        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://instagram.fxxx.fbcdn.net/sunset.jpg", image.url)
    }

    @Test
    fun `resolve should parse instagram reel with video`() = runTest {
        val embedHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Reel by jane_dancer on Instagram" />
            <meta property="og:description" content="New dance routine! 💃" />
            <meta property="og:image" content="https://instagram.fxxx.fbcdn.net/poster.jpg" />
            <meta property="og:video" content="https://instagram.fxxx.fbcdn.net/dance.mp4" />
        </head>
        <body>
            <div class="Caption">
                <a class="CaptionUsername" href="/jane_dancer/">jane_dancer</a>
            </div>
            <video class="EmbeddedMediaVideo" src="https://instagram.fxxx.fbcdn.net/dance.mp4" poster="https://instagram.fxxx.fbcdn.net/poster.jpg"></video>
        </body>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(embedHtml)
        }

        val post = resolver.resolve("https://instagram.com/reel/Cx12345abc/", client)

        assertEquals(Platform.INSTAGRAM, post.platform)
        assertEquals("jane_dancer", post.author.username)
        assertEquals(1, post.media.size)
        val video = post.media.first() as Media.Video
        assertEquals("https://instagram.fxxx.fbcdn.net/dance.mp4", video.url)
        assertEquals("https://instagram.fxxx.fbcdn.net/poster.jpg", video.previewUrl)
    }

    @Test
    fun `resolve should throw PostNotFoundException on 404 response`() = runTest {
        val client = createMockHttpClient {
            htmlResponse("Not Found", status = HttpStatusCode.NotFound)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://www.instagram.com/p/invalid/", client)
        }
    }
}
