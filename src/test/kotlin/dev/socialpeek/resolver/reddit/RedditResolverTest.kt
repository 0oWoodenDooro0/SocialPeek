package dev.socialpeek.resolver.reddit

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.htmlResponse
import dev.socialpeek.test.jsonResponse
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedditResolverTest {

    private val resolver = RedditResolver()

    @Test
    fun `canResolve should match reddit post URLs and share URLs`() {
        assertTrue(resolver.canResolve("https://www.reddit.com/r/kotlindev/comments/12345/awesome_library/"))
        assertTrue(resolver.canResolve("https://reddit.com/r/kotlindev/comments/12345"))
        assertTrue(resolver.canResolve("https://old.reddit.com/r/kotlindev/comments/12345"))
        assertTrue(resolver.canResolve("https://redd.it/12345"))
        assertTrue(resolver.canResolve("https://www.reddit.com/r/google_antigravity/s/7GwvvFKRsE"))
        assertTrue(resolver.canResolve("https://reddit.com/s/7GwvvFKRsE"))
        assertFalse(resolver.canResolve("https://reddit.com/r/kotlindev/"))
        assertFalse(resolver.canResolve("https://reddit.com/user/someone/"))
        assertFalse(resolver.canResolve("https://twitter.com/jack/status/20"))
    }

    @Test
    fun `resolve should parse text post correctly`() = runTest {
        val mockJson = """
        [
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "t3_12345",
                                "title": "SocialPeek Released!",
                                "selftext": "A brand new social media metadata parser for Kotlin.",
                                "author": "kotlin_dev",
                                "subreddit": "kotlindev",
                                "ups": 350,
                                "num_comments": 42,
                                "permalink": "/r/kotlindev/comments/12345/socialpeek_released/",
                                "created_utc": 1715000000.0
                            }
                        }
                    ]
                }
            }
        ]
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(mockJson)
        }

        val post = resolver.resolve("https://www.reddit.com/r/kotlindev/comments/12345/socialpeek_released/", client)

        assertEquals(Platform.REDDIT, post.platform)
        assertEquals("t3_12345", post.id)
        assertEquals("SocialPeek Released!", post.title)
        assertEquals("A brand new social media metadata parser for Kotlin.", post.content)
        assertEquals("kotlin_dev", post.author.username)
        assertEquals("https://www.reddit.com/user/kotlin_dev", post.author.profileUrl)
        assertEquals(350, post.metrics?.likes)
        assertEquals(42, post.metrics?.comments)
        assertEquals(1715000000L, post.createdAtEpochSeconds)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun `resolve should fallback to oEmbed when JSON API fails`() = runTest {
        val oEmbedJson = """
        {
            "title": "Account Disabled",
            "author_name": "xethorn",
            "provider_name": "Reddit"
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath.contains("oembed")) {
                jsonResponse(oEmbedJson)
            } else {
                respond("Blocked", HttpStatusCode.Forbidden)
            }
        }

        val post = resolver.resolve("https://www.reddit.com/r/google_antigravity/comments/1wbqdaj/account_disabled/", client)

        assertEquals(Platform.REDDIT, post.platform)
        assertEquals("1wbqdaj", post.id)
        assertEquals("Account Disabled", post.title)
        assertEquals("xethorn", post.author.username)
        assertEquals("https://www.reddit.com/user/xethorn", post.author.profileUrl)
        assertEquals(1, post.media.size)
    }

    @Test
    fun `resolve should follow redirect for share link and fallback to oEmbed`() = runTest {
        val oEmbedJson = """
        {
            "title": "Account Disabled",
            "author_name": "xethorn",
            "provider_name": "Reddit"
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath.contains("/s/")) {
                respond(
                    content = "",
                    status = HttpStatusCode.MovedPermanently,
                    headers = headersOf(HttpHeaders.Location, "https://www.reddit.com/r/google_antigravity/comments/1wbqdaj/account_disabled/")
                )
            } else if (request.url.encodedPath.contains("oembed")) {
                jsonResponse(oEmbedJson)
            } else {
                respond("Blocked", HttpStatusCode.Forbidden)
            }
        }

        val post = resolver.resolve("https://www.reddit.com/r/google_antigravity/s/7GwvvFKRsE", client)
        assertEquals("1wbqdaj", post.id)
        assertEquals("Account Disabled", post.title)
        assertEquals("xethorn", post.author.username)
    }

    @Test
    fun `resolve should parse single image post`() = runTest {
        val mockJson = """
        [
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "img123",
                                "title": "Cute Cat",
                                "selftext": "",
                                "author": "cat_lover",
                                "subreddit": "aww",
                                "post_hint": "image",
                                "url": "https://i.redd.it/cat_picture.jpg",
                                "preview": {
                                    "images": [
                                        {
                                            "source": {
                                                "url": "https://preview.redd.it/cat_picture.jpg?auto=webp&amp;s=abc",
                                                "width": 1080,
                                                "height": 720
                                            }
                                        }
                                    ]
                                },
                                "ups": 1500,
                                "num_comments": 50
                            }
                        }
                    ]
                }
            }
        ]
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(mockJson)
        }

        val post = resolver.resolve("https://reddit.com/r/aww/comments/img123/cute_cat/", client)

        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://i.redd.it/cat_picture.jpg", image.url)
        assertEquals(1080, image.width)
        assertEquals(720, image.height)
    }

    @Test
    fun `resolve should throw PostNotFoundException when post does not exist`() = runTest {
        val client = createMockHttpClient {
            respond("Not Found", HttpStatusCode.NotFound)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://reddit.com/comments/unknown", client)
        }
    }
}
