package dev.socialpeek.resolver.reddit

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.jsonResponse
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
    fun `canResolve should match reddit URLs`() {
        assertTrue(resolver.canResolve("https://www.reddit.com/r/Kotlin/comments/1cdefgh/check_this_out/"))
        assertTrue(resolver.canResolve("https://reddit.com/comments/1cdefgh"))
        assertTrue(resolver.canResolve("https://redd.it/1cdefgh"))
        assertTrue(resolver.canResolve("http://old.reddit.com/r/programming/comments/1cdefgh/"))
        assertFalse(resolver.canResolve("https://reddit.com/r/Kotlin"))
        assertFalse(resolver.canResolve("https://x.com/jack/status/20"))
    }

    @Test
    fun `resolve should parse text post with upvotes and comments`() = runTest {
        val mockJson = """
        [
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "1cdefgh",
                                "title": "Kotlin 2.0 is great",
                                "selftext": "Here are my detailed thoughts about the new compiler...",
                                "author": "kotlin_dev",
                                "subreddit": "Kotlin",
                                "ups": 350,
                                "num_comments": 42,
                                "created_utc": 1715000000.0,
                                "permalink": "/r/Kotlin/comments/1cdefgh/kotlin_20_is_great/"
                            }
                        }
                    ]
                }
            }
        ]
        """.trimIndent()

        val client = createMockHttpClient { request ->
            assertTrue(request.url.encodedPath.contains("1cdefgh"))
            jsonResponse(mockJson)
        }

        val post = resolver.resolve("https://redd.it/1cdefgh", client)

        assertEquals(Platform.REDDIT, post.platform)
        assertEquals("1cdefgh", post.id)
        assertEquals("Kotlin 2.0 is great", post.title)
        assertEquals("Here are my detailed thoughts about the new compiler...", post.content)
        assertEquals("kotlin_dev", post.author.username)
        assertEquals("https://www.reddit.com/user/kotlin_dev", post.author.profileUrl)
        assertEquals(350, post.metrics?.likes)
        assertEquals(42, post.metrics?.comments)
        assertEquals(1715000000L, post.createdAtEpochSeconds)
        assertTrue(post.media.isEmpty())
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
    fun `resolve should parse gallery post`() = runTest {
        val mockJson = """
        [
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "gal123",
                                "title": "Trip Photos",
                                "selftext": "Enjoying the vacation",
                                "author": "traveler",
                                "is_gallery": true,
                                "gallery_data": {
                                    "items": [
                                        { "media_id": "photo1" },
                                        { "media_id": "photo2" }
                                    ]
                                },
                                "media_metadata": {
                                    "photo1": {
                                        "status": "valid",
                                        "e": "Image",
                                        "s": {
                                            "u": "https://preview.redd.it/photo1.jpg?width=1000&amp;format=pjpg&amp;auto=webp&amp;s=111",
                                            "x": 1000,
                                            "y": 600
                                        }
                                    },
                                    "photo2": {
                                        "status": "valid",
                                        "e": "Image",
                                        "s": {
                                            "u": "https://preview.redd.it/photo2.jpg?width=1200&amp;format=pjpg&amp;auto=webp&amp;s=222",
                                            "x": 1200,
                                            "y": 800
                                        }
                                    }
                                }
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

        val post = resolver.resolve("https://reddit.com/comments/gal123", client)

        assertEquals(2, post.media.size)
        val img1 = post.media[0] as Media.Image
        val img2 = post.media[1] as Media.Image
        assertEquals("https://i.redd.it/photo1.jpg", img1.url)
        assertEquals(1000, img1.width)
        assertEquals(600, img1.height)
        assertEquals("https://i.redd.it/photo2.jpg", img2.url)
    }

    @Test
    fun `resolve should parse video post`() = runTest {
        val mockJson = """
        [
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "vid123",
                                "title": "Epic Gameplay",
                                "selftext": "",
                                "author": "gamer",
                                "is_video": true,
                                "media": {
                                    "reddit_video": {
                                        "fallback_url": "https://v.redd.it/vid123/DASH_1080.mp4?source=fallback",
                                        "duration": 45,
                                        "width": 1920,
                                        "height": 1080,
                                        "bitrate_kbps": 4500
                                    }
                                }
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

        val post = resolver.resolve("https://reddit.com/comments/vid123", client)

        assertEquals(1, post.media.size)
        val video = post.media.first() as Media.Video
        assertEquals("https://v.redd.it/vid123/DASH_1080.mp4?source=fallback", video.url)
        assertEquals(45.0, video.durationSeconds)
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
        assertEquals(4500000L, video.bitrate)
    }

    @Test
    fun `resolve should throw PostNotFoundException when post does not exist`() = runTest {
        val client = createMockHttpClient {
            jsonResponse("[]", status = HttpStatusCode.NotFound)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://reddit.com/comments/unknown", client)
        }
    }
}
