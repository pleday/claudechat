package dev.claudechat

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.JPanel

/** Astuces affichées à l'ouverture d'une nouvelle conversation, par groupes de trois. */
object Tips {
    const val GROUP = 3

    val all = listOf(
        "Tape @ pour mentionner un fichier du projet : il est joint au message.",
        "Tape / pour une commande : /explain, /fix, /tests, /doc, /review, ou toute commande de la CLI.",
        "Entrée envoie, Maj+Entrée ajoute une ligne, Échap interrompt Claude.",
        "Pendant une réponse, « Préciser » ajuste la consigne sans interrompre ; « Mettre en file d'attente » envoie le message après.",
        "Colle une image avec Ctrl+V, ou joins des fichiers, images et vidéos avec le bouton +.",
        "Une vidéo jointe devient quelques images (via ffmpeg) ; la piste audio n'est pas transmise.",
        "Clic droit sur du code, puis « Envoyer la sélection à Claude Chat ».",
        "La batterie en bas indique ton quota de 5 h : clique dessus pour le détail sur 5 h et 7 jours.",
        "Active l'interrupteur 📱 pour répondre à Claude depuis ton téléphone, via Telegram.",
        "Le mode « Plan » fait proposer un plan à valider avant toute action.",
        "Active la reprise automatique : Claude repart seul quand ton quota se réinitialise.",
        "Clique sur le fichier courant (sous la saisie) pour joindre ou non le contexte de l'éditeur.",
        "Ouvre plusieurs conversations avec le + de la fenêtre, ou reprends une ancienne session avec l'horloge.",
    )

    val groups: Int get() = (all.size + GROUP - 1) / GROUP

    /** Les trois astuces du groupe `i` (le groupe boucle). */
    fun group(i: Int): List<String> {
        val start = Math.floorMod(i, groups) * GROUP
        return (0 until GROUP).map { all[(start + it) % all.size] }
    }
}

/** Portrait de l'écran d'accueil : l'image `/images/avatar.png` (PNG à coins arrondis), remplaçable. */
object Avatar {
    fun load(resource: String = "/images/avatar.png"): BufferedImage? =
        runCatching { Avatar::class.java.getResourceAsStream(resource)?.use { javax.imageio.ImageIO.read(it) } }.getOrNull()
}

/** Affiche une image à sa proportion, réduite avec un lissage de qualité. */
class AvatarComponent(private val image: BufferedImage, private val maxHeight: Int = 170) : javax.swing.JComponent() {
    init { isOpaque = false }

    private fun scaled(): Dimension {
        val h = minOf(JBUI.scale(maxHeight), height.takeIf { it > 0 } ?: Int.MAX_VALUE, image.height)
        val w = (image.width * h.toDouble() / image.height).toInt()
        return Dimension(w, h)
    }

    override fun getPreferredSize(): Dimension {
        val h = minOf(JBUI.scale(maxHeight), image.height)
        return Dimension((image.width * h.toDouble() / image.height).toInt(), h)
    }

    override fun getMaximumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val d = scaled()
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.drawImage(image, (width - d.width) / 2, (height - d.height) / 2, d.width, d.height, null)
        } finally { g2.dispose() }
    }
}

/** Écran d'accueil d'une conversation vide : portrait, salutation et astuces. */
class WelcomePanel(avatar: BufferedImage?) : JPanel() {
    private var group = (Math.random() * Tips.groups).toInt()
    private val tipsLabel = JBLabel()

    override fun getBackground(): java.awt.Color = UIUtil.getEditorPaneBackground()

    init {
        isOpaque = true
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(16)
        fun <T : Component> T.centered(): T = also { (it as javax.swing.JComponent).alignmentX = Component.CENTER_ALIGNMENT }

        add(Box.createVerticalGlue())
        avatar?.let { add(AvatarComponent(it).centered()); add(Box.createVerticalStrut(JBUI.scale(12))) }
        add(JBLabel("Salut ! Sur quoi on travaille ?").apply {
            font = font.deriveFont(java.awt.Font.BOLD, font.size2D + 3f)
        }.centered())
        add(Box.createVerticalStrut(JBUI.scale(14)))
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(10, 14),
            )
            add(tipsLabel)
        }.also { card ->
            // la carte garde sa hauteur naturelle (BoxLayout l'étirerait sinon)
            card.maximumSize = null
        }.let { card ->
            object : JPanel(java.awt.BorderLayout()) {
                override fun getMaximumSize() = Dimension(JBUI.scale(460), preferredSize.height)
            }.apply { isOpaque = false; add(card) }
        }.centered())
        add(Box.createVerticalStrut(JBUI.scale(6)))
        add(ActionLink("Astuces suivantes ›") { nextTips() }.centered())
        add(Box.createVerticalGlue())
        refresh()
    }

    fun shuffle() { group = (Math.random() * Tips.groups).toInt(); refresh() }
    fun nextTips() { group++; refresh() }
    val currentTips: List<String> get() = Tips.group(group)

    private fun refresh() {
        // Swing ne respecte la largeur que sur <body> : sans elle le texte ne retourne pas à la ligne
        tipsLabel.text = "<html><body width='${JBUI.scale(380)}'><b>💡 Astuces</b><br/>" +
            Tips.group(group).joinToString("<br/>") { "• " + it.replace("&", "&amp;").replace("<", "&lt;") } + "</body></html>"
    }
}
