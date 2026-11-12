package dev.claudechat

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

enum class BatteryLevel { UNKNOWN, GREEN, ORANGE, RED }

/** Vert de 100 % à 50 % restants, orange jusqu'à 20 %, rouge en dessous. */
fun batteryLevel(remaining: Int?): BatteryLevel = when {
    remaining == null -> BatteryLevel.UNKNOWN
    remaining >= 50 -> BatteryLevel.GREEN
    remaining >= 20 -> BatteryLevel.ORANGE
    else -> BatteryLevel.RED
}

/** Icône de jauge « batterie » : remplissage = quota restant (0..100), null = inconnu. */
class BatteryIcon(private val remaining: Int?, private val bodyW: Int = 22, private val bodyH: Int = 11) : Icon {
    private val nub = 2
    override fun getIconWidth() = JBUI.scale(bodyW + nub + 1)
    override fun getIconHeight() = JBUI.scale(bodyH + 2)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = JBUI.scale(bodyW); val h = JBUI.scale(bodyH); val arc = JBUI.scale(4)
            val top = y + JBUI.scale(1)
            val outline = JBColor(java.awt.Color(0x6E6E6E), java.awt.Color(0xA0A0A0))
            g2.color = outline
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            g2.drawRoundRect(x, top, w - 1, h - 1, arc, arc)
            g2.fillRect(x + w, top + h / 4, JBUI.scale(nub), h / 2)
            val level = batteryLevel(remaining)
            if (remaining != null) {
                g2.color = when (level) {
                    BatteryLevel.GREEN -> JBColor(java.awt.Color(0x3C9B4A), java.awt.Color(0x57A64A))
                    BatteryLevel.ORANGE -> JBColor(java.awt.Color(0xE08A00), java.awt.Color(0xF0A030))
                    else -> JBColor(java.awt.Color(0xD64545), java.awt.Color(0xE05555))
                }
                val pad = JBUI.scale(2)
                val full = w - 2 * pad
                val fill = (full * remaining.coerceIn(0, 100) / 100).let { if (remaining > 0) it.coerceAtLeast(JBUI.scale(2)) else 0 }
                g2.fillRoundRect(x + pad, top + pad, fill, h - 2 * pad, JBUI.scale(2), JBUI.scale(2))
            } else {
                g2.color = outline
                g2.drawString("?", x + w / 2 - JBUI.scale(3), top + h - JBUI.scale(2))
            }
        } finally { g2.dispose() }
    }
}
