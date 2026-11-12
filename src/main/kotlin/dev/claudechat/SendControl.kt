package dev.claudechat

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** Couleur d'accent de l'interface (bordure active, bouton d'envoi). */
val ACCENT = JBColor(Color(0xD9, 0x77, 0x57), Color(0xD9, 0x77, 0x57))

/** Une entrée de menu ; `checked` affiche la coche du choix en cours. */
class SplitItem(val text: String, val icon: Icon?, val tooltip: String?, val checked: Boolean = false, val run: () -> Unit)

/** Affiche un popup au-dessus de son ancre (les contrôles sont en bas de l'écran). */
fun showAbove(popup: JBPopup, anchor: Component, alignRight: Boolean = false) {
    val size = popup.content.preferredSize
    val x = if (alignRight) anchor.width - size.width else 0
    popup.show(com.intellij.ui.awt.RelativePoint(anchor, Point(x, -size.height - JBUI.scale(2))))
}

/** Libellé cliquable, plat, avec survol : « + », mode de permission, fichier courant, modèle… */
class FlatButton(text: String, icon: Icon? = null, private val onClick: (FlatButton) -> Unit) : JLabel(text, icon, LEADING) {
    private var hover = false
    var dimmed: Boolean = false
        set(v) { field = v; foreground = if (v) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground(); repaint() }

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(3, 6)
        iconTextGap = JBUI.scale(4)
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent?) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent?) { if (isEnabled) onClick(this@FlatButton) }
        })
    }

    override fun paintComponent(g: Graphics) {
        if (hover && isEnabled) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(Color(0, 0, 0, 28), Color(255, 255, 255, 34))
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
            g2.dispose()
        }
        super.paintComponent(g)
    }

    fun click() = onClick(this)
}

/** Anneau de remplissage du contexte, suivi du pourcentage. */
class ContextRing : JComponent() {
    var percent: Int = 0
        set(v) { field = v.coerceIn(0, 100); repaint() }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font ?: UIUtil.getLabelFont())
        return Dimension(JBUI.scale(18) + JBUI.scale(4) + fm.stringWidth("100%"), JBUI.scale(20))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val d = JBUI.scale(14); val x = JBUI.scale(2); val y = (height - d) / 2
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.color = JBColor(Color(0, 0, 0, 40), Color(255, 255, 255, 50))
            g2.drawOval(x, y, d, d)
            if (percent > 0) {
                g2.color = when { percent >= 90 -> JBColor.RED; percent >= 70 -> JBColor.ORANGE; else -> UIUtil.getContextHelpForeground() }
                g2.drawArc(x, y, d, d, 90, -(360 * percent / 100).coerceAtLeast(8))
            }
            g2.font = font ?: UIUtil.getLabelFont(); g2.color = UIUtil.getContextHelpForeground()
            val fm = g2.fontMetrics
            g2.drawString("$percent%", JBUI.scale(22), (height + fm.ascent - fm.descent) / 2)
        } finally { g2.dispose() }
    }
}

/** Bouton d'envoi rond : flèche pour envoyer, carré pour interrompre. */
class RoundSendButton : JComponent() {
    enum class Mode { SEND, STOP }
    var mode = Mode.SEND
        set(v) { field = v; repaint() }
    var onClick: () -> Unit = {}
    private var hover = false

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent?) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent?) { click() }
        })
    }

    fun click() { if (isEnabled) onClick() }

    override fun getPreferredSize() = Dimension(JBUI.scale(28), JBUI.scale(28))
    override fun getMinimumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val d = minOf(width, height) - 2
            val x = (width - d) / 2; val y = (height - d) / 2
            g2.color = when {
                !isEnabled -> Color(ACCENT.red, ACCENT.green, ACCENT.blue, 90)
                hover -> ACCENT.brighter()
                else -> ACCENT
            }
            g2.fillOval(x, y, d, d)
            g2.color = Color.WHITE
            if (mode == Mode.STOP) {
                val s = (d * 0.34).toInt()
                g2.fillRoundRect(x + (d - s) / 2, y + (d - s) / 2, s, s, 3, 3)
            } else {
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val cx = x + d / 2
                val top = y + (d * 0.28).toInt(); val bottom = y + (d * 0.72).toInt(); val wing = (d * 0.2).toInt()
                g2.drawLine(cx, bottom, cx, top)
                g2.drawLine(cx, top, cx - wing, top + wing)
                g2.drawLine(cx, top, cx + wing, top + wing)
            }
        } finally { g2.dispose() }
    }
}

/** Bouton d'envoi rond + chevron ouvrant un menu à coche (mettre en file d'attente / préciser). */
class SendControl : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)) {
    val round = RoundSendButton()
    private var items: List<SplitItem> = emptyList()
    private val chevron = FlatButton("", AllIcons.General.ArrowDown) { showMenu() }.apply {
        toolTipText = "Choisir le comportement pendant une réponse"
        border = JBUI.Borders.empty(6, 2)
    }

    init { isOpaque = false; add(round); add(chevron) }

    fun configure(mode: RoundSendButton.Mode, enabled: Boolean, tooltip: String?, menu: List<SplitItem>, run: () -> Unit) {
        round.mode = mode; round.isEnabled = enabled; round.toolTipText = tooltip; round.onClick = run
        items = menu; chevron.isVisible = menu.isNotEmpty()
        revalidate(); repaint()
    }

    val hasMenu: Boolean get() = chevron.isVisible
    fun clickMain() = round.click()
    fun runItem(index: Int) = items[index].run()
    val menuItems: List<SplitItem> get() = items

    private fun showMenu() {
        if (items.isEmpty()) return
        val blank = EmptyIcon.ICON_16
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setRenderer(object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(l: javax.swing.JList<*>?, v: Any?, i: Int, sel: Boolean, f: Boolean): Component {
                    val c = super.getListCellRendererComponent(l, v, i, sel, f) as JLabel
                    (v as? SplitItem)?.let { c.text = it.text; c.icon = if (it.checked) AllIcons.Actions.Checked else blank; c.toolTipText = it.tooltip }
                    c.border = JBUI.Borders.empty(4, 8)
                    c.iconTextGap = JBUI.scale(8)
                    return c
                }
            })
            .setItemChosenCallback { it.run() }
            .createPopup()
        showAbove(popup, this, alignRight = true)
    }
}

/** FlowLayout qui revient à la ligne quand la largeur manque, en recalculant la hauteur demandée. */
class WrapFlowLayout(align: Int = FlowLayout.LEFT, private val h: Int = 6, private val v: Int = 3) : FlowLayout(align, h, v) {
    override fun preferredLayoutSize(target: java.awt.Container) = size(target, true)
    override fun minimumLayoutSize(target: java.awt.Container) = size(target, false).also { it.width -= h + 1 }

    /** Largeur nécessaire pour tout afficher sur une seule ligne. */
    fun naturalWidth(target: java.awt.Container): Int = size(target, true, Int.MAX_VALUE).width

    /** Hauteur nécessaire pour une largeur donnée. */
    fun heightFor(target: java.awt.Container, width: Int): Int = size(target, true, width).height

    private fun size(target: java.awt.Container, preferred: Boolean, forWidth: Int = target.width.takeIf { it > 0 } ?: Int.MAX_VALUE): Dimension = synchronized(target.treeLock) {
        val insets = target.insets
        val extra = insets.left + insets.right + h * 2
        val maxWidth = forWidth.let { if (it == Int.MAX_VALUE) it else it - extra }
        var width = 0; var height = 0; var rowW = 0; var rowH = 0
        fun flush() { width = maxOf(width, rowW); if (height > 0) height += v; height += rowH }
        for (c in target.components) {
            if (!c.isVisible) continue
            val d = if (preferred) c.preferredSize else c.minimumSize
            if (rowW > 0 && rowW + h + d.width > maxWidth) { flush(); rowW = 0; rowH = 0 }
            if (rowW > 0) rowW += h
            rowW += d.width; rowH = maxOf(rowH, d.height)
        }
        flush()
        Dimension(width + extra, height + insets.top + insets.bottom + v * 2)
    }
}

/**
 * Pied de carte : contrôles à gauche (qui passent à la ligne) et groupe de droite (modèle, contexte, envoi).
 * Quand les deux ne tiennent pas côte à côte, le groupe de droite passe sous celui de gauche, aligné à droite.
 */
class FooterLayout(private val gap: Int = 8) : java.awt.LayoutManager {
    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}

    private fun parts(t: Container) = t.components[0] as JPanel to t.components[1] as JPanel

    private fun leftNatural(left: JPanel) = (left.layout as? WrapFlowLayout)?.naturalWidth(left) ?: left.preferredSize.width
    private fun leftHeight(left: JPanel, w: Int) = (left.layout as? WrapFlowLayout)?.heightFor(left, w) ?: left.preferredSize.height

    private fun sideBySide(t: Container, left: JPanel, right: JPanel, avail: Int) =
        avail <= 0 || leftNatural(left) + gap + right.preferredSize.width <= avail

    override fun preferredLayoutSize(t: Container): Dimension = synchronized(t.treeLock) {
        val (left, right) = parts(t)
        val ins = t.insets
        val avail = t.width - ins.left - ins.right
        val rp = right.preferredSize
        val h = if (sideBySide(t, left, right, avail)) maxOf(leftHeight(left, maxOf(avail - rp.width - gap, 1)), rp.height)
        else leftHeight(left, avail) + 2 + rp.height
        Dimension(leftNatural(left) + gap + rp.width + ins.left + ins.right, h + ins.top + ins.bottom)
    }

    override fun minimumLayoutSize(t: Container) = preferredLayoutSize(t)

    override fun layoutContainer(t: Container) = synchronized(t.treeLock) {
        val (left, right) = parts(t)
        val ins = t.insets
        val avail = t.width - ins.left - ins.right
        val rp = right.preferredSize
        if (sideBySide(t, left, right, avail)) {
            val lw = avail - rp.width - gap
            val h = maxOf(leftHeight(left, lw), rp.height)
            left.setBounds(ins.left, ins.top, lw, h)
            right.setBounds(ins.left + avail - rp.width, ins.top + h - rp.height, rp.width, rp.height)
        } else {
            val lh = leftHeight(left, avail)
            left.setBounds(ins.left, ins.top, avail, lh)
            val rw = minOf(rp.width, avail)
            right.setBounds(ins.left + avail - rw, ins.top + lh + 2, rw, rp.height)
        }
    }
}

/**
 * Carte de saisie : pièces jointes / file d'attente en haut, texte au milieu, puis une barre :
 * à gauche les contrôles, à droite modèle, contexte et envoi. La bordure prend la couleur d'accent au focus.
 */
fun composerCard(
    input: JComponent, top: JComponent, focusOwner: JComponent,
    left: List<Component>, right: List<Component>,
): JPanel {
    val leftRow = JPanel(WrapFlowLayout(FlowLayout.LEFT, 2, 2)).apply { isOpaque = false; left.forEach { add(it) } }
    val rightRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false; right.forEach { add(it) } }
    val footer = JPanel(FooterLayout(8)).apply { isOpaque = false; add(leftRow); add(rightRow) }
    fun border(focused: Boolean) = BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(if (focused) ACCENT else JBColor.border(), 1, true),
        BorderFactory.createEmptyBorder(6, 8, 4, 6),
    )
    val card = JPanel(BorderLayout(0, 2)).apply {
        this.border = border(false)
        add(top, BorderLayout.NORTH)
        add(input, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }
    focusOwner.addFocusListener(object : FocusAdapter() {
        override fun focusGained(e: FocusEvent?) { card.border = border(true); card.repaint() }
        override fun focusLost(e: FocusEvent?) { card.border = border(false); card.repaint() }
    })
    // la hauteur du pied dépend de sa largeur : on recalcule quand elle change
    footer.addComponentListener(object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent?) { card.revalidate() }
    })
    return card
}
