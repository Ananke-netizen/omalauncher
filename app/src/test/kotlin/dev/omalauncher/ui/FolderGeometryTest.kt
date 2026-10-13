package dev.omalauncher.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class FolderGeometryTest {
    // A Pixel 7's home grid at density 1: six columns of square cells, gapped like HomeGrid's.
    private val gap = 6
    private val cell = (1080 - gap) / 6f
    private val rows = 10
    private val page = IntRect(0, 0, 1080, (rows * cell).roundToInt() + gap)

    /** A 1x1 tile's rect, as HomeGrid's Grid.rect places it. */
    private fun tile(
        x: Int,
        y: Int,
        side: Int = 0,
    ) = IntRect((gap + x * cell).roundToInt(), (gap + y * cell).roundToInt(), ((x + 1) * cell).roundToInt(), ((y + 1) * cell).roundToInt())
        .translate(side * page.width, 0)

    /** Where [page]'s tiles go: inside the gap to its edges, here the same as between tiles. */
    private fun tiles(page: IntRect) = IntRect(page.left + gap, page.top + gap, page.right - gap, page.bottom - gap)

    private fun geometry(
        anchor: IntRect?,
        count: Int,
        area: IntRect = tiles(page),
    ) = FolderGeometry(area, anchor, cell, cell, gap, count, Density(1f))

    private fun IntRect.contains(r: IntRect) = r.left >= left && r.top >= top && r.right <= right && r.bottom <= bottom

    @Test fun opensUpAndToTheRightOfItsTile() {
        val from = tile(0, 6)
        val panel = geometry(from, 4).panel
        assertEquals(from.left, panel.left)
        assertEquals(from.bottom, panel.bottom)
    }

    @Test fun inTheTopRightCornerItOpensDownAndToTheLeft() {
        val from = tile(5, 0)
        val panel = geometry(from, 4).panel
        assertEquals(from.right, panel.right)
        assertEquals(from.top, panel.top)
    }

    @Test fun alwaysCoversItsTileAndStaysOnItsPage() {
        val inside = tiles(page)
        for (count in 2..20) {
            for (x in 0 until 6) {
                for (y in 0 until rows) {
                    val from = tile(x, y)
                    val g = geometry(from, count)
                    assertTrue("$count apps from ($x, $y): ${g.panel} covers $from", g.panel.contains(from))
                    assertTrue("$count apps from ($x, $y): ${g.panel} inside $inside", inside.contains(g.panel))
                    for (i in 0 until count) assertTrue(g.panel.contains(g.app(i)))
                }
            }
        }
    }

    @Test fun unfoldedItStaysOnTheFoldersSideOfTheFold() {
        val right = page.translate(page.width, 0)
        // The left column of the right page: opening left would cross the fold.
        val g = geometry(tile(0, 9, side = 1), 9, tiles(right))
        assertTrue(g.panel.left >= right.left + gap)
    }

    @Test fun withWiderEdgesItStaysWhereTheTilesAre() {
        // style.toml's widgets.gaps-out at 24, the gap between tiles still 6: the tiles start 24 from the edges.
        val area = IntRect(24, 24, 1080 - 24, 1800)
        val cell = (area.width + gap) / 6f
        val rows = ((area.height + gap) / cell).toInt()
        for (count in 2..20) {
            for (x in 0 until 6) {
                for (y in 0 until rows) {
                    val from =
                        IntRect(
                            (area.left + x * cell).roundToInt(),
                            (area.top + y * cell).roundToInt(),
                            (area.left + (x + 1) * cell - gap).roundToInt(),
                            (area.top + (y + 1) * cell - gap).roundToInt(),
                        )
                    val g = FolderGeometry(area, from, cell, cell, gap, count, Density(1f))
                    assertTrue("$count apps from ($x, $y): ${g.panel} covers $from", g.panel.contains(from))
                    assertTrue("$count apps from ($x, $y): ${g.panel} inside $area", area.contains(g.panel))
                }
            }
        }
    }

    @Test fun withoutATileItsCentered() {
        val panel = geometry(null, 4).panel
        assertEquals(page.width - panel.right, panel.left, 1)
        assertEquals(page.height - panel.bottom, panel.top, 1)
    }

    @Test fun appsFillInFromTheTileOutward() {
        // Opening up and right from the bottom-left: 9 apps in 3x3, the bottom-left slot over the tile.
        val g = geometry(tile(0, 8), 9)
        assertEquals(0f, g.wave(6), 0f)
        assertEquals(1f, g.wave(2), 0f)
        assertEquals(g.wave(3), g.wave(7), 0f)
        assertTrue(g.wave(6) < g.wave(3) && g.wave(3) < g.wave(0) && g.wave(0) < g.wave(2))
    }

    @Test fun theNameSitsOverTheAppsInsideThePanel() {
        for (count in 2..20) {
            for ((x, y) in listOf(0 to 0, 5 to 0, 2 to 5, 0 to rows - 1, 5 to rows - 1)) {
                val g = geometry(tile(x, y), count)
                assertTrue("$count apps from ($x, $y): ${g.name} inside ${g.panel}", g.panel.contains(g.name))
                assertTrue(g.naming.contains(g.name))
                for (i in 0 until count) assertTrue("app $i under the name", g.app(i).top >= g.name.bottom)
                // Lined up with the first column's tiles.
                assertEquals(g.app(0).left, g.name.left)
            }
        }
    }

    @Test fun tappingAboveTheAppsTakesATouchTarget() {
        val g = geometry(tile(2, 5), 6)
        assertTrue("${g.naming.height} px tall", g.naming.height >= 48)
        assertEquals(g.panel.width, g.naming.width)
    }

    @Test fun aLargerFontGetsATallerName() {
        val normal = FolderGeometry(tiles(page), tile(2, 5), cell, cell, gap, 6, Density(1f))
        val large = FolderGeometry(tiles(page), tile(2, 5), cell, cell, gap, 6, Density(1f, fontScale = 2f))
        assertTrue("${large.name.height} > ${normal.name.height}", large.name.height > normal.name.height)
        assertTrue(large.panel.contains(large.name))
    }

    @Test fun phasesClampToTheirStretch() {
        assertEquals(0f, phase(0.1f, 0.3f, 0.6f), 0f)
        assertEquals(0.5f, phase(0.45f, 0.3f, 0.6f), 1e-6f)
        assertEquals(1f, phase(0.9f, 0.3f, 0.6f), 0f)
    }

    private fun assertEquals(
        expected: Int,
        actual: Int,
        delta: Int,
    ) = assertTrue("$actual within $delta of $expected", kotlin.math.abs(expected - actual) <= delta)
}
