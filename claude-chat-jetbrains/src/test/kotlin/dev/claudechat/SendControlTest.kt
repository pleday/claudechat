package dev.claudechat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.*

/** Rendu hors écran : les contrôles de la carte de saisie doivent exister, être peints et rester visibles. */
class SendControlTest {
    private val icon = ImageIcon(BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB))

    private fun paint(c: Component): BufferedImage {
        c.setSize(c.preferredSize)
        val img = BufferedImage(maxOf(c.width, 1), maxOf(c.height, 1), BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics(); c.paint(g); g.dispose(); return img
    }

    private fun painted(img: BufferedImage) = (0 until img.width).any { x -> (0 until img.height).any { y -> img.getRGB(x, y) ushr 24 != 0 } }

    private fun layoutAll(c: Container) { c.doLayout(); c.components.forEach { (it as? Container)?.let(::layoutAll) } }

    @Test fun roundButtonHasSizeAndPaintsBothModes() {
        val b = RoundSendButton()
        assertTrue(b.preferredSize.width >= 20 && b.preferredSize.height >= 20)
        assertTrue(painted(paint(b)))
        b.mode = RoundSendButton.Mode.STOP
        assertTrue(painted(paint(b)))
        b.isEnabled = false
        assertTrue(painted(paint(b)), "désactivé : toujours visible, estompé")
    }

    @Test fun clickRunsOnlyWhenEnabled() {
        var n = 0
        val c = SendControl()
        c.configure(RoundSendButton.Mode.SEND, false, null, emptyList()) { n++ }
        c.clickMain(); assertEquals(0, n)
        c.configure(RoundSendButton.Mode.SEND, true, null, emptyList()) { n++ }
        c.clickMain(); assertEquals(1, n)
    }

    @Test fun chevronOnlyWhenThereIsAMenuAndItemsKeepTheirCheckMark() {
        var chosen = ""
        val c = SendControl()
        c.configure(RoundSendButton.Mode.SEND, true, null, emptyList()) {}
        assertFalse(c.hasMenu)
        c.configure(RoundSendButton.Mode.SEND, true, null, listOf(
            SplitItem("Mettre en file d'attente", icon, null, checked = false) { chosen = "queue" },
            SplitItem("Préciser l'exécution en cours", icon, null, checked = true) { chosen = "refine" },
        )) {}
        assertTrue(c.hasMenu)
        assertEquals(listOf(false, true), c.menuItems.map { it.checked })
        c.runItem(0); assertEquals("queue", chosen)
    }

    @Test fun ringClampsAndPaints() {
        val r = ContextRing()
        r.percent = 250; assertEquals(100, r.percent)
        r.percent = -4; assertEquals(0, r.percent)
        r.percent = 42; assertTrue(painted(paint(r)))
        assertTrue(r.preferredSize.width > 20)
    }

    private fun card(width: Int): Triple<JPanel, List<Component>, List<Component>> {
        val send = SendControl().apply { configure(RoundSendButton.Mode.SEND, true, null, listOf(SplitItem("a", icon, null) {})) {} }
        val left = listOf<Component>(FlatButton("", icon) {}, FlatButton("Demander", icon) {}, FlatButton("MonFichierAssezLong.kt", icon) {})
        val right = listOf<Component>(FlatButton("Sonnet 5.5 · high", null) {}, ContextRing(), send)
        val input = JTextArea(3, 20)
        val c = composerCard(JScrollPane(input), JPanel(), input, left, right)
        repeat(3) { c.setSize(width, c.preferredSize.height); layoutAll(c) }
        return Triple(c, left, right)
    }

    @Test fun everyControlStaysVisibleAndNothingOverlapsAtAnyWidth() {
        for (width in listOf(900, 735, 500, 340, 300)) {
            val (c, left, right) = card(width)
            fun rect(x: Component) = SwingUtilities.convertRectangle(x.parent, x.bounds, c)
            val all = (left + right).map { it to rect(it) }
            for ((comp, r) in all) {
                assertTrue(r.width > 0 && r.height > 0, "${comp.javaClass.simpleName} invisible à $width px")
                assertTrue(r.x >= 0 && r.x + r.width <= width, "${comp.javaClass.simpleName} coupé à droite à $width px : $r")
                assertTrue(r.y >= 0 && r.y + r.height <= c.height, "${comp.javaClass.simpleName} coupé en bas à $width px : $r / h=${c.height}")
            }
            val rightRects = right.map { rect(it) }
            for (l in left.map { rect(it) }) for (r in rightRects) assertFalse(l.intersects(r), "chevauchement à $width px : $l / $r")
        }
    }

    @Test fun leftControlsWrapOnNarrowPanelsOnly() {
        fun rows(parts: List<Component>) = parts.map { it.y + it.height / 2 }.sorted()
            .fold(emptyList<Int>()) { acc, c -> if (acc.isEmpty() || c - acc.last() > 3) acc + c else acc }.size
        assertEquals(1, rows(card(1000).second))
        assertTrue(rows(card(300).second) > 1)
    }
}
