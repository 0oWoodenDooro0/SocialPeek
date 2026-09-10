package dev.socialpeek.resolver.bilibili

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

class BilibiliResolverTest {

    private val resolver = BilibiliResolver()

    @Test
    fun `canResolve should match bilibili video, opus, and b23-tv URLs`() {
        assertTrue(resolver.canResolve("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertTrue(resolver.canResolve("https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=333.337"))
        assertTrue(resolver.canResolve("https://www.bilibili.com/opus/123456789012345678"))
        assertTrue(resolver.canResolve("https://t.bilibili.com/123456789012345678"))
        assertTrue(resolver.canResolve("https://b23.tv/BV1xx411c7mD"))
        assertFalse(resolver.canResolve("https://bilibili.com/bangumi/play/ep123"))
        assertFalse(resolver.canResolve("https://youtube.com/watch?v=123"))
    }

    @Test
    fun `resolve should parse bilibili video correctly`() = runTest {
        val videoMockJson = """
        {
            "code": 0,
            "message": "0",
            "data": {
                "bvid": "BV1xx411c7mD",
                "aid": 170001,
                "title": "【初音ミク】測試影片標題",
                "desc": "這是影片詳細說明文案",
                "pic": "https://i0.hdslb.com/bfs/archive/cover.jpg",
                "pubdate": 1600000000,
                "duration": 210,
                "owner": {
                    "mid": 9999,
                    "name": "B站知名UP主",
                    "face": "https://i0.hdslb.com/bfs/face/avatar.jpg"
                },
                "stat": {
                    "view": 500000,
                    "danmaku": 12000,
                    "reply": 3500,
                    "favorite": 40000,
                    "like": 65000,
                    "share": 8000
                }
            }
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            assertTrue(request.url.encodedPath.contains("x/web-interface/view"))
            assertEquals("BV1xx411c7mD", request.url.parameters["bvid"])
            jsonResponse(videoMockJson)
        }

        val post = resolver.resolve("https://www.bilibili.com/video/BV1xx411c7mD", client)

        assertEquals(Platform.BILIBILI, post.platform)
        assertEquals("BV1xx411c7mD", post.id)
        assertEquals("【初音ミク】測試影片標題", post.title)
        assertEquals("這是影片詳細說明文案", post.content)
        assertEquals("B站知名UP主", post.author.displayName)
        assertEquals("9999", post.author.username)
        assertEquals("https://space.bilibili.com/9999", post.author.profileUrl)
        assertEquals(1600000000L, post.createdAtEpochSeconds)
        assertEquals(65000L, post.metrics?.likes)
        assertEquals(3500L, post.metrics?.comments)
        assertEquals(500000L, post.metrics?.views)
        assertEquals(8000L, post.metrics?.reposts)
        assertEquals(40000L, post.metrics?.bookmarks)
        assertEquals(1, post.media.size)
        val video = post.media.first() as Media.Video
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", video.url)
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", video.previewUrl)
        assertEquals(210.0, video.durationSeconds)
    }

    @Test
    fun `resolve should parse bilibili opus dynamic with multiple images`() = runTest {
        val opusMockJson = """
        {
            "code": 0,
            "message": "0",
            "data": {
                "item": {
                    "id_str": "987654321098765432",
                    "modules": {
                        "module_author": {
                            "mid": 12345,
                            "name": "繪師UP主",
                            "face": "https://i0.hdslb.com/face.jpg",
                            "pub_ts": 1710000000
                        },
                        "module_dynamic": {
                            "desc": {
                                "text": "今天畫了幾張插畫，分享給大家！"
                            },
                            "major": {
                                "opus": {
                                    "title": "插畫圖集",
                                    "pics": [
                                        { "url": "https://i0.hdslb.com/bfs/new_dyn/p1.jpg", "width": 1920, "height": 1080 },
                                        { "url": "https://i0.hdslb.com/bfs/new_dyn/p2.jpg", "width": 1080, "height": 1080 }
                                    ]
                                }
                            }
                        },
                        "module_stat": {
                            "like": { "count": 888 },
                            "comment": { "count": 66 },
                            "forward": { "count": 22 }
                        }
                    }
                }
            }
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            assertTrue(request.url.encodedPath.contains("detail"))
            assertEquals("987654321098765432", request.url.parameters["id"])
            jsonResponse(opusMockJson)
        }

        val post = resolver.resolve("https://www.bilibili.com/opus/987654321098765432", client)

        assertEquals(Platform.BILIBILI, post.platform)
        assertEquals("987654321098765432", post.id)
        assertEquals("插畫圖集", post.title)
        assertEquals("今天畫了幾張插畫，分享給大家！", post.content)
        assertEquals("繪師UP主", post.author.displayName)
        assertEquals("12345", post.author.username)
        assertEquals(888L, post.metrics?.likes)
        assertEquals(66L, post.metrics?.comments)
        assertEquals(22L, post.metrics?.reposts)
        assertEquals(2, post.media.size)
        val p1 = post.media[0] as Media.Image
        assertEquals("https://i0.hdslb.com/bfs/new_dyn/p1.jpg", p1.url)
        assertEquals(1920, p1.width)
        assertEquals(1080, p1.height)
    }

    @Test
    fun `resolve should throw PostNotFoundException when bilibili returns non-zero code`() = runTest {
        val errorJson = """
        {
            "code": -404,
            "message": "啥都木有",
            "ttl": 1
        }
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(errorJson)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://www.bilibili.com/video/BV1xx411c7mD", client)
        }
    }
}
