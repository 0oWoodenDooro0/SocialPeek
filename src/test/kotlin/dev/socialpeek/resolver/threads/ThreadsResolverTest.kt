package dev.socialpeek.resolver.threads

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.htmlResponse
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ThreadsResolverTest {

    private val resolver = ThreadsResolver()

    @Test
    fun `canResolve should match valid Threads post URLs`() {
        assertTrue(resolver.canResolve("https://www.threads.net/@zuck/post/CuP48CiS5sx"))
        assertTrue(resolver.canResolve("https://threads.net/@zuck/post/CuP48CiS5sx"))
        assertTrue(resolver.canResolve("https://www.threads.net/t/CuP48CiS5sx"))
        assertTrue(resolver.canResolve("https://threads.net/t/CuP48CiS5sx"))
        assertTrue(resolver.canResolve("https://threads.com/@user/post/12345"))
        assertTrue(resolver.canResolve("https://www.threads.net/share/BAENHoOpq1/"))
        assertTrue(resolver.canResolve("https://threads.com/share/BAENHoOpq1/"))
    }

    @Test
    fun `canResolve should reject non-threads URLs`() {
        assertFalse(resolver.canResolve("https://www.instagram.com/p/CuP48CiS5sx"))
        assertFalse(resolver.canResolve("https://twitter.com/zuck/status/123456"))
        assertFalse(resolver.canResolve("https://www.threads.net/@zuck"))
    }

    @Test
    fun `resolve should extract post info from threads page`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Mark Zuckerberg (@zuck) on Threads" />
            <meta property="og:description" content="Welcome to Threads! Glad you are all here." />
            <meta property="og:image" content="https://scontent.cdninstagram.com/photo.jpg" />
            <meta property="og:image:width" content="1080" />
            <meta property="og:image:height" content="1080" />
            <meta property="og:url" content="https://www.threads.net/@zuck/post/CuP48CiS5sx" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        val post = resolver.resolve("https://www.threads.net/@zuck/post/CuP48CiS5sx", client)

        assertEquals(Platform.THREADS, post.platform)
        assertEquals("CuP48CiS5sx", post.id)
        assertEquals("zuck", post.author.username)
        assertEquals("Mark Zuckerberg", post.author.displayName)
        assertEquals("https://www.threads.net/@zuck", post.author.profileUrl)
        assertEquals("Welcome to Threads! Glad you are all here.", post.content)
        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://scontent.cdninstagram.com/photo.jpg", image.url)
        assertEquals(1080, image.width)
        assertEquals(1080, image.height)
    }

    @Test
    fun `resolve should parse text-only post without media and map avatar to author`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Mark Zuckerberg (@zuck) on Threads" />
            <meta property="og:description" content="Let's do this. Welcome to Threads. 🔥" />
            <meta property="og:image" content="https://instagram.fbcdn.net/v/t51.82787-19/avatar.jpg?efg=profile_pic" />
            <meta name="twitter:card" content="summary" />
            <meta property="og:url" content="https://www.threads.net/@zuck/post/CuP48CiS5sx" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        val post = resolver.resolve("https://www.threads.net/@zuck/post/CuP48CiS5sx", client)

        assertEquals("CuP48CiS5sx", post.id)
        assertEquals("zuck", post.author.username)
        assertEquals("Mark Zuckerberg", post.author.displayName)
        assertEquals("Let's do this. Welcome to Threads. 🔥", post.content)
        assertEquals("https://instagram.fbcdn.net/v/t51.82787-19/avatar.jpg?efg=profile_pic", post.author.avatarUrl)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun `resolve should filter out 1200x628 platform share card banner for text post`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Mark Zuckerberg (@zuck) on Threads" />
            <meta property="og:description" content="Let's do this. Welcome to Threads. 🔥" />
            <meta property="og:image" content="https://scontent.fbcdn.net/v/t39.92108-6/802988515_preview.jpg" />
            <meta property="og:image:width" content="1200" />
            <meta property="og:image:height" content="628" />
            <meta name="twitter:card" content="summary_large_image" />
            <meta property="og:url" content="https://www.threads.net/@zuck/post/CuP48CiS5sx" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        val post = resolver.resolve("https://www.threads.net/@zuck/post/CuP48CiS5sx", client)

        assertEquals("CuP48CiS5sx", post.id)
        assertEquals("Let's do this. Welcome to Threads. 🔥", post.content)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun `resolve should extract all images from multi-image carousel post with null video_versions`() = runTest {
        val scriptJson = """
        {
            "require": [
                ["RelayPrefetchedStreamCache", "next", [], [{
                    "data": {
                        "user": {
                            "username": "zuck",
                            "full_name": "Mark Zuckerberg",
                            "profile_pic_url": "https://instagram.fbcdn.net/avatar.jpg"
                        },
                        "carousel_media": [
                            {
                                "__typename": "XIGPolarisImageMedia",
                                "video_versions": null,
                                "image_versions2": {
                                    "candidates": [
                                        { "url": "https://instagram.fbcdn.net/photo1.jpg", "width": 1440, "height": 1440 }
                                    ]
                                }
                            },
                            {
                                "__typename": "XIGPolarisImageMedia",
                                "video_versions": null,
                                "image_versions2": {
                                    "candidates": [
                                        { "url": "https://instagram.fbcdn.net/photo2.jpg", "width": 1440, "height": 1440 }
                                    ]
                                }
                            },
                            {
                                "__typename": "XIGPolarisImageMedia",
                                "video_versions": null,
                                "image_versions2": {
                                    "candidates": [
                                        { "url": "https://instagram.fbcdn.net/photo3.jpg", "width": 1440, "height": 1440 }
                                    ]
                                }
                            }
                        ]
                    }
                }]]
            ]
        }
        """.trimIndent()

        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Mark Zuckerberg (@zuck) on Threads" />
            <meta property="og:description" content="Look at these 3 AI creations!" />
            <meta property="og:image" content="https://instagram.fbcdn.net/photo1.jpg" />
            <script type="application/json">$scriptJson</script>
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        val post = resolver.resolve("https://www.threads.net/@zuck/post/C9xxwZZyx5B", client)

        assertEquals("C9xxwZZyx5B", post.id)
        assertEquals("zuck", post.author.username)
        assertEquals("Mark Zuckerberg", post.author.displayName)
        assertEquals("https://instagram.fbcdn.net/avatar.jpg", post.author.avatarUrl)
        assertEquals(3, post.media.size)
        assertEquals("https://instagram.fbcdn.net/photo1.jpg", (post.media[0] as Media.Image).url)
        assertEquals("https://instagram.fbcdn.net/photo2.jpg", (post.media[1] as Media.Image).url)
        assertEquals("https://instagram.fbcdn.net/photo3.jpg", (post.media[2] as Media.Image).url)
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

        val client = createMockHttpClient {
            when (it.url.encodedPath) {
                "/share/BAENHoOpq1/" -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://www.threads.net/@snowisland.jp/post/CuP48CiS5sx")
                )
                else -> htmlResponse(pageHtml)
            }
        }

        val post = resolver.resolve("https://www.threads.net/share/BAENHoOpq1/", client)
        assertEquals("CuP48CiS5sx", post.id)
        assertEquals("snowisland.jp", post.author.username)
    }

    @Test
    fun `resolve should throw PostNotFoundException when post does not exist`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Page Not Found &bull; Threads</title>
        </head>
        <body>
            <div>Sorry, this page isn't available.</div>
        </body>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://www.threads.net/@zuck/post/invalidId", client)
        }
    }
}
