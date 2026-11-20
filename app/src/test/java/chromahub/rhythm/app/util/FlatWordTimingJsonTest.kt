/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatWordTimingJsonTest {
    @Test
    fun parsesFlatWordTimingJsonAndGroupsByLineIndex() {
        val json = """
            [
              {"word":"I","start":18.03,"end":18.16,"confidence":0.896,"line_index":1},
              {"word":"wanna","start":18.16,"end":18.6,"confidence":0.745,"line_index":1},
              {"word":"be","start":18.72,"end":18.91,"confidence":0.91,"line_index":1},
              {"word":"yours","start":19.0,"end":19.42,"confidence":0.88,"line_index":2}
            ]
        """.trimIndent()

        val parsed = RhythmLyricsParser.parseWordByWordLyrics(json)

        assertEquals(2, parsed.size)
        assertEquals("I wanna be", parsed[0].words.joinToString(" ") { it.text })
        assertEquals(18030L, parsed[0].lineTimestamp)
        assertEquals(18600L, parsed[0].lineEndtime)
        assertEquals(18030L, parsed[0].words[0].timestamp)
        assertEquals(18160L, parsed[0].words[1].timestamp)
        assertEquals("yours", parsed[1].words.single().text)
        assertTrue(RhythmLyricsParser.hasWordTiming(parsed))
    }

    @Test
    fun flatWordTimingJsonRoundTripsThroughCanonicalFormat() {
        val json = """[{"word":"hello","start":1.25,"end":1.7,"confidence":0.9,"line_index":0}]"""

        val parsed = RhythmLyricsParser.parseWordByWordLyrics(json)
        val canonical = RhythmLyricsParser.toWordByWordJson(parsed)
        val reparsed = RhythmLyricsParser.parseWordByWordLyrics(canonical)

        assertEquals(1, reparsed.size)
        assertEquals("hello", reparsed.single().words.single().text)
        assertEquals(1250L, reparsed.single().words.single().timestamp)
        assertEquals(1700L, reparsed.single().words.single().endtime)
    }
}
