package dev.claudechat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class WelcomeTest {
    @Test fun bundledAvatarLoadsWithTransparentRoundedCorners() {
        val img = Avatar.load()
        assertNotNull(img, "avatar.png doit se charger")
        assertTrue(img!!.width in 100..600 && img.height in 100..600, "taille ${img.width}x${img.height}")
        assertEquals(0, img.getRGB(0, 0) ushr 24, "coin arrondi transparent")
        assertEquals(255, img.getRGB(img.width / 2, img.height / 2) ushr 24, "centre opaque")
        assertNull(Avatar.load("/images/inexistante.png"))
    }

    @Test fun avatarComponentKeepsProportionsAndPaints() {
        val img = Avatar.load()!!
        val c = AvatarComponent(img, 170)
        val d = c.preferredSize
        assertEquals(img.width.toDouble() / img.height, d.width.toDouble() / d.height, 0.02)
        assertTrue(d.height <= 170 * 3)
        c.setSize(d)
        val out = BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics(); c.paint(g); g.dispose()
        assertTrue(out.getRGB(d.width / 2, d.height / 2) ushr 24 == 255, "centre peint")
    }

    @Test fun tipsAreWellFormedAndGroupsWrap() {
        assertEquals(Tips.all.size, Tips.all.toSet().size, "astuces uniques")
        assertTrue(Tips.all.all { it.length in 20..180 }, "longueur raisonnable")
        assertEquals(Tips.GROUP, Tips.group(0).size)
        assertEquals(Tips.group(0), Tips.group(Tips.groups), "le groupe boucle")
        assertEquals(Tips.group(Tips.groups - 1), Tips.group(-1))
        assertEquals(Tips.all.toSet(), (0 until Tips.groups).flatMap { Tips.group(it) }.toSet(), "toutes les astuces sont atteignables")
    }

    @Test fun welcomePanelRendersAndCyclesTips() {
        val w = WelcomePanel(Avatar.load())
        val before = w.currentTips
        w.nextTips()
        assertNotEquals(before, w.currentTips)
        w.setSize(520, 520); w.doLayout()
        val img = BufferedImage(520, 520, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics(); w.paint(g); g.dispose()
        assertTrue((0 until 520 step 4).any { x -> (0 until 520 step 4).any { y -> img.getRGB(x, y) ushr 24 != 0 } })
    }

    @Test fun speakerHeadersAreReadableAndEscaped() {
        val h = assistantHeader("#D97757", "Sonnet 5.5", gap = true)
        assertTrue("Claude" in h && "#D97757" in h && "Sonnet 5.5" in h && "bgcolor='#D97757'" in h)
        assertFalse("height='10'" in assistantHeader("#D97757", null, gap = false))
        assertTrue("&lt;b&gt;" in speakerLabel("<b>", "#fff", "a & b") && "a &amp; b" in speakerLabel("x", "#fff", "a & b"))
        assertFalse("<font color='gray'" in speakerLabel("Vous", "#2F6FDE", null))
    }
}
