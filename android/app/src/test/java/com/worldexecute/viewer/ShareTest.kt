package com.worldexecute.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The share gate is the one piece of this app that is security rather than
 * presentation: when the socket faces the network, [Share.strip] returning null
 * is the only thing between a stranger on the Wi-Fi and a file server.
 *
 * All of it is string logic, so it can be checked here rather than by pointing a
 * laptop at a phone.
 */
class ShareTest {

    private val token = "AB12CD34"

    @Test
    fun `a token is the advertised length and avoids ambiguous glyphs`() {
        repeat(200) {
            val t = Share.newToken()
            assertEquals(Share.TOKEN_LENGTH, t.length)
            for (c in t) {
                assertTrue(
                    "token contains $c, which is not in the alphabet",
                    "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".contains(c),
                )
            }
        }
    }

    @Test
    fun `tokens differ`() {
        val seen = HashSet<String>()
        repeat(500) { seen.add(Share.newToken()) }
        // 32^8 is 1.1e12, so a collision in 500 draws means the generator is
        // not random at all.
        assertEquals(500, seen.size)
    }

    @Test
    fun `a seeded generator is reproducible`() {
        assertEquals(
            Share.newToken(Random(7)),
            Share.newToken(Random(7)),
        )
    }

    @Test
    fun `the prefix is the share root only while sharing`() {
        assertEquals("/", Share.prefix(null))
        assertEquals("/", Share.prefix(""))
        assertEquals("/s/AB12CD34/", Share.prefix(token))
    }

    @Test
    fun `loopback mode passes every path straight through`() {
        assertEquals("/index.html", Share.strip(null, "/index.html"))
        assertEquals("/src/00_core.js", Share.strip(null, "/src/00_core.js"))
        assertEquals("/", Share.strip(null, "/"))
    }

    @Test
    fun `sharing mode strips the token off the front`() {
        assertEquals("/index.html", Share.strip(token, "/s/AB12CD34/index.html"))
        assertEquals("/src/00_core.js", Share.strip(token, "/s/AB12CD34/src/00_core.js"))
        assertEquals("/assets/audio.mp3", Share.strip(token, "/s/AB12CD34/assets/audio.mp3"))
    }

    @Test
    fun `the bare share root is the film, not a 404`() {
        assertEquals("/", Share.strip(token, "/s/AB12CD34"))
        assertEquals("/", Share.strip(token, "/s/AB12CD34/"))
    }

    @Test
    fun `a request without the token reaches nothing`() {
        assertNull(Share.strip(token, "/index.html"))
        assertNull(Share.strip(token, "/"))
        assertNull(Share.strip(token, "/s/index.html"))
        assertNull(Share.strip(token, "/assets/audio.mp3"))
    }

    @Test
    fun `a wrong token reaches nothing`() {
        assertNull(Share.strip(token, "/s/AB12CD35/index.html"))
        assertNull(Share.strip(token, "/s/ZZZZZZZZ/index.html"))
    }

    @Test
    fun `a token that is a prefix of another does not open the door`() {
        // The difference between `startsWith` on "/s/AB12CD/" and on
        // "/s/AB12CD34/". Only the second one may pass, and only for its own
        // token.
        assertNull(Share.strip(token, "/s/AB12CD/index.html"))
        assertNull(Share.strip(token, "/s/AB12CD345/index.html"))
    }

    @Test
    fun `tokens are case sensitive`() {
        assertNull(Share.strip(token, "/s/ab12cd34/index.html"))
        assertEquals("/index.html", Share.strip("ab12cd34", "/s/ab12cd34/index.html"))
    }

    @Test
    fun `the gate runs before the canonical check, not instead of it`() {
        // `strip` is a door, not a sanitiser: a valid token still leaves `..`
        // in the path exactly where it was, for AssetServer's canonical-path
        // check to refuse. If this ever started normalising, that would be a
        // second, weaker traversal check quietly replacing the first one.
        assertEquals("/../secrets", Share.strip(token, "/s/AB12CD34/../secrets"))
    }
}
