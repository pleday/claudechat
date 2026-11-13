package dev.claudechat

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

const val MCP_DOCS_URL = "https://docs.claude.com/en/docs/claude-code/mcp"

data class BadgeStyle(val text: String, val bg: Color, val fg: Color)

fun badgeStyle(s: McpStatus): BadgeStyle = when (s) {
    McpStatus.CONNECTED -> BadgeStyle("✓ Connecté", Color(0x1E9E4A), Color.WHITE)
    McpStatus.NEEDS_AUTH -> BadgeStyle("! Authentification requise", Color(0xE8B000), Color(0x2A2000))
    McpStatus.FAILED -> BadgeStyle("✗ Échec de connexion", Color(0xC94040), Color.WHITE)
    McpStatus.PENDING -> BadgeStyle("⏸ En attente d'approbation", Color(0x5C6B80), Color.WHITE)
    McpStatus.CHECKING -> BadgeStyle("… Vérification", Color(0x6B6F76), Color.WHITE)
}

/** Pastille colorée à coins arrondis (statut d'un serveur). */
class Badge(style: BadgeStyle) : JComponent() {
    var style: BadgeStyle = style
        set(v) { field = v; revalidate(); repaint() }

    private fun fm() = getFontMetrics((font ?: UIUtil.getLabelFont()).deriveFont(Font.BOLD, (font ?: UIUtil.getLabelFont()).size2D - 1f))

    override fun getPreferredSize(): Dimension = Dimension(fm().stringWidth(style.text) + JBUI.scale(18), fm().height + JBUI.scale(6))
    override fun getMaximumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = style.bg
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
            g2.font = (font ?: UIUtil.getLabelFont()).deriveFont(Font.BOLD, (font ?: UIUtil.getLabelFont()).size2D - 1f)
            g2.color = style.fg
            val m = g2.fontMetrics
            g2.drawString(style.text, (width - m.stringWidth(style.text)) / 2, (height + m.ascent - m.descent) / 2)
        } finally { g2.dispose() }
    }
}

/** Liste des serveurs groupée par portée : une carte par serveur (nom + badge), détails au clic. */
class McpListPanel(private val onRemove: (McpServer) -> Unit, private val onCopy: (String) -> Unit) : JPanel() {
    private var servers: List<McpServer> = emptyList()
    private var filter = ""
    private val expanded = HashSet<String>()

    /** Pour les tests et l'accessibilité : en-têtes de section et nombre de cartes affichés. */
    var sectionTitles: List<String> = emptyList(); private set
    var rowCount = 0; private set

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(6, 12)
        isOpaque = false
    }

    fun setServers(list: List<McpServer>) { servers = list; rebuild() }
    fun setFilter(f: String) { filter = f.trim().lowercase(); rebuild() }
    fun toggle(s: McpServer) { val k = key(s); if (!expanded.add(k)) expanded.remove(k); rebuild() }

    private fun key(s: McpServer) = "${s.scope}:${s.name}"
    private fun fixedHeight(layout: java.awt.LayoutManager) = object : JPanel(layout) {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun rebuild() {
        removeAll()
        val shown = servers.filter { filter.isEmpty() || it.name.lowercase().contains(filter) || it.target.lowercase().contains(filter) }
        val titles = ArrayList<String>()
        rowCount = shown.size
        McpScope.values().forEach { scope ->
            val group = shown.filter { it.scope == scope }
            if (group.isEmpty()) return@forEach
            titles += "${scope.title} (${group.size})"
            add(sectionHeader(scope, group.size))
            group.forEach { add(card(it)); add(Box.createVerticalStrut(JBUI.scale(6))) }
        }
        sectionTitles = titles
        if (shown.isEmpty()) add(JBLabel(if (servers.isEmpty()) "Aucun serveur MCP configuré." else "Aucun résultat.").apply {
            foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(20, 4)
        })
        revalidate(); repaint()
    }

    private fun sectionHeader(scope: McpScope, n: Int): JComponent = fixedHeight(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(10, 2, 6, 2)
        add(JBLabel("${scope.title} ($n)").apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
        add(JBLabel(scope.source).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size - 2)
        }, BorderLayout.EAST)
    }

    private fun card(s: McpServer): JComponent {
        val card = fixedHeight(BorderLayout(0, 6)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true), JBUI.Borders.empty(8, 10),
            )
        }
        val top = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            add(JBLabel(s.name).apply { font = Font(Font.MONOSPACED, Font.PLAIN, font.size) }, BorderLayout.CENTER)
            add(Badge(badgeStyle(s.status)), BorderLayout.EAST)
            addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent?) { toggle(s) } })
        }
        card.add(top, BorderLayout.NORTH)
        if (key(s) in expanded) card.add(details(s), BorderLayout.CENTER)
        return card
    }

    private fun details(s: McpServer): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JBLabel(McpServers.detailsHtml(s)).apply { foreground = UIUtil.getContextHelpForeground(); alignmentX = 0f })
        if (s.status == McpStatus.NEEDS_AUTH) {
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(JBLabel(
                if (s.scope == McpScope.CLAUDE_AI) "Connecte ce service dans les paramètres de ton compte claude.ai (Connecteurs)."
                else "Pour t'authentifier : lance `claude` dans un terminal, puis tape /mcp.",
            ).apply { alignmentX = 0f })
        }
        add(Box.createVerticalStrut(JBUI.scale(6)))
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false; alignmentX = 0f
            if (s.scope.removable) add(JButton("Supprimer").apply { addActionListener { onRemove(s) } })
            if (s.status == McpStatus.NEEDS_AUTH && s.scope != McpScope.CLAUDE_AI) add(JButton("Copier /mcp").apply { addActionListener { onCopy("/mcp") } })
            add(JButton("Copier le nom").apply { addActionListener { onCopy(s.name) } })
        })
    }
}

/** Fenêtre « Serveurs MCP » : liste groupée par portée avec statuts, recherche, actualisation et ajout. */
class McpDialog(private val project: Project?, private val cwd: String) : DialogWrapper(project, true) {
    private val cli get() = ClaudeSettings.get().cliPath()
    private val statusLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val search = SearchTextField(false).apply { isVisible = false }
    private val list = McpListPanel(onRemove = { remove(it) }, onCopy = { CopyPasteManager.getInstance().setContents(StringSelection(it)) })
    private var configs: List<McpServer> = emptyList()
    private var generation = 0

    init {
        title = "Serveurs MCP"
        setOKButtonText("Fermer")
        search.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = list.setFilter(search.text)
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = list.setFilter(search.text)
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {}
        })
        init()
        reload()
    }

    override fun createActions() = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 12, 2, 8)
            add(JBLabel("Serveurs MCP").apply { font = font.deriveFont(Font.BOLD, font.size2D + 2f) }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                add(statusLabel)
                add(FlatButton("", AllIcons.Actions.Refresh) { reload() }.apply { toolTipText = "Actualiser (vérifie à nouveau la connexion de chaque serveur)" })
                add(FlatButton("", AllIcons.Actions.Find) {
                    search.isVisible = !search.isVisible
                    if (search.isVisible) search.textEditor.requestFocusInWindow() else { search.text = ""; list.setFilter("") }
                    search.parent?.revalidate()
                }.apply { toolTipText = "Rechercher un serveur" })
                add(FlatButton("", AllIcons.General.Add) { McpAddDialog(project, cwd) { reload() }.show() }.apply { toolTipText = "Ajouter un serveur MCP" })
            }, BorderLayout.EAST)
        }
        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(0, 12); add(search) }, BorderLayout.SOUTH)
        }
        val scroll = JBScrollPane(JPanel(BorderLayout()).apply { add(list, BorderLayout.NORTH) }).apply { border = JBUI.Borders.empty() }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(560))
            add(top, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()), JBUI.Borders.empty(8, 12))
                add(ActionLink("En savoir plus sur MCP") { BrowserUtil.browse(MCP_DOCS_URL) }, BorderLayout.WEST)
            }, BorderLayout.SOUTH)
        }
    }

    /** Affiche aussitôt les serveurs configurés, puis complète avec les statuts (la CLI vérifie chaque serveur). */
    private fun reload() {
        val gen = ++generation
        configs = McpServers.readConfigs(cwd)
        list.setServers(configs)
        statusLabel.text = "Vérification des serveurs…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val listed = McpServers.list(cli, cwd)
            ApplicationManager.getApplication().invokeLater({
                if (gen != generation || isDisposed) return@invokeLater
                list.setServers(McpServers.merge(configs, listed))
                statusLabel.text = if (listed.isEmpty()) "Aucune réponse de la CLI" else ""
            }, ModalityState.any())
        }
    }

    private fun remove(s: McpServer) {
        if (Messages.showYesNoDialog(project, "Supprimer le serveur « ${s.name} » (${s.scope.title}) ?", "Supprimer", null) != Messages.YES) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val (ok, msg) = McpServers.remove(cli, cwd, s.name, s.scope)
            ApplicationManager.getApplication().invokeLater({
                if (!ok) Messages.showErrorDialog(project, msg, "Suppression impossible")
                if (!isDisposed) reload()
            }, ModalityState.any())
        }
    }
}

/** Fenêtre « Ajouter un serveur MCP » : nom (facultatif si le JSON a « mcpServers »), portée et configuration JSON. */
class McpAddDialog(private val project: Project?, private val cwd: String, private val onAdded: () -> Unit) : DialogWrapper(project, true) {
    private val nameField = JBTextField().apply { emptyText.setText("mon-serveur") }
    private val scopes = listOf(
        "Utilisateur (tous les projets)" to McpScope.USER,
        "Projet (partagé, .mcp.json)" to McpScope.PROJECT,
        "Local (ce projet, privé)" to McpScope.LOCAL,
    )
    private val scopeBox = com.intellij.openapi.ui.ComboBox(scopes.map { it.first }.toTypedArray())
    private val json = JBTextArea(9, 48).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        emptyText.setText("{ \"mcpServers\": { \"mon-serveur\": { \"command\": \"npx\", \"args\": [\"-y\", \"mon-serveur-mcp\"] } } }")
    }
    private val error = JBLabel().apply { foreground = JBColor.RED }

    init {
        title = "Ajouter un serveur MCP"
        setOKButtonText("Ajouter le serveur")
        setCancelButtonText("Annuler")
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = json

    override fun createCenterPanel(): JComponent {
        fun hint(t: String) = JBLabel(t).apply { foreground = UIUtil.getContextHelpForeground(); font = font.deriveFont(font.size2D - 1f); alignmentX = 0f }
        fun label(t: String) = JBLabel(t).apply { alignmentX = 0f; border = JBUI.Borders.empty(8, 0, 2, 0) }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 4)
            add(label("Nom")); add(nameField.apply { alignmentX = 0f })
            add(hint("Facultatif — utilisé uniquement si ton JSON n'a pas de wrapper « mcpServers »"))
            add(label("Portée")); add(scopeBox.apply { alignmentX = 0f; maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height) })
            add(label("Configuration (JSON)")); add(JBScrollPane(json).apply { alignmentX = 0f })
            add(hint("Colle une configuration mcpServers ou la configuration d'un seul serveur"))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(error.apply { alignmentX = 0f })
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (json.text.isBlank()) return ValidationInfo("Colle une configuration JSON.", json)
        McpServers.normalize(json.text, nameField.text).error?.let { return ValidationInfo(it, json) }
        return null
    }

    override fun doOKAction() {
        val servers = McpServers.normalize(json.text, nameField.text).servers
        val scope = scopes[scopeBox.selectedIndex].second
        val cli = ClaudeSettings.get().cliPath()
        isOKActionEnabled = false
        error.text = ""
        ApplicationManager.getApplication().executeOnPooledThread {
            val failures = servers.mapNotNull { (n, j) -> McpServers.add(cli, cwd, n, j, scope).let { (ok, msg) -> if (ok) null else "$n : $msg" } }
            ApplicationManager.getApplication().invokeLater({
                if (failures.isEmpty()) { super.doOKAction(); onAdded() }
                else { error.text = "<html>" + failures.joinToString("<br/>") { it.replace("<", "&lt;") } + "</html>"; isOKActionEnabled = true }
            }, ModalityState.any())
        }
    }
}
