package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.Alarm
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

/** Panneau de conversation piloté par la CLI `claude` (stream-json). */
class ChatPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val settings = ClaudeSettings.get()
    private var session = Session()
    private var streaming = false
    private var generation = 0
    private var renderPending = false
    private var turn: CliTurn? = null
    private var idleTurn: CliTurn? = null
    private var interrupted = false
    private var forkNext = false
    private var remoteOn = settings.state.remoteDefault
    private val phoneSink = object : RemoteHub.Sink {
        override fun onPhoneMessage(text: String) {
            ApplicationManager.getApplication().invokeLater({ receivePhone(text) }, ModalityState.any())
        }
    }
    var content: com.intellij.ui.content.Content? = null
    private var customTitle: String? = null
    private var activeModel: String? = null
    private var liveCtx = 0
    private var liveOut = 0
    private var turnStart = 0L
    private var autoResumeOn = settings.state.autoResume
    private var lastLimits: RateLimits? = null
    private var resumeAt = 0L
    private var resumeAttempts = 0
    private var quotaError: String? = null
    private var popupRef: com.intellij.openapi.ui.popup.JBPopup? = null
    private var noQuotaLastTurn = false
    private var searchQuery = ""
    private var costUsd = 0.0
    private var openKind: Kind? = null
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private val cwd: String get() = project.basePath ?: System.getProperty("user.home")

    // Affichage (FR-03, FR-05)
    private var showThinking = false
    private var showTools = true

    // Permissions (FR-40..44)
    private inner class Interaction(
        val req: PermissionRequest, val title: String, val body: String,
        val options: List<String>, val freeText: Boolean, val resolve: (String) -> Unit,
    ) {
        var handle: RemoteHub.Handle? = null
        var done = false
    }
    private val pending = ArrayDeque<Interaction>()
    private var current: Interaction? = null
    private var allowAll = false
    private val editedDocs = HashMap<String, com.intellij.openapi.editor.Document>()
    private val proposed = HashMap<String, String>()

    // Messages empilés pendant que Claude travaille (FR-09)
    private val queue = mutableListOf<String>()

    private val view = JEditorPane().apply {
        isEditable = false
        editorKit = HTMLEditorKitBuilder().withGapsBetweenParagraphs().build()
        border = JBUI.Borders.empty(8)
        addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val d = e.description ?: return@addHyperlinkListener
            if (d.startsWith("open:")) openFile(d.removePrefix("open:")) else e.url?.let { BrowserUtil.browse(it) }
        }
    }
    private val scroll = JBScrollPane(view)
    private val welcome = WelcomePanel(Avatar.load())
    private val transcriptCards = JPanel(java.awt.CardLayout()).apply {
        add(scroll, "chat")
        add(JBScrollPane(welcome).apply { border = JBUI.Borders.empty() }, "welcome")
    }
    private val input = object : JBTextArea(3, 20) {
        override fun paste() {
            if (!pasteFromClipboard(null, "paste()")) super.paste()
        }
    }.apply {
        lineWrap = true
        // Ctrl+V est souvent traité par l'IDE / le TransferHandler sans passer par paste() : on intercepte aussi ces chemins
        val mask = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        for (k in listOf(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, mask), KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_INSERT, java.awt.event.InputEvent.SHIFT_DOWN_MASK))) {
            inputMap.put(k, "claude-paste")
        }
        actionMap.put("claude-paste", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { LOG.info("Collage : raccourci Ctrl+V"); paste() }
        })
        val textHandler = transferHandler
        transferHandler = object : javax.swing.TransferHandler() {
            private fun ours(f: Array<out java.awt.datatransfer.DataFlavor>) =
                f.any { it == java.awt.datatransfer.DataFlavor.imageFlavor || it == java.awt.datatransfer.DataFlavor.javaFileListFlavor }
            override fun canImport(c: javax.swing.JComponent, f: Array<out java.awt.datatransfer.DataFlavor>) = ours(f) || textHandler.canImport(c, f)
            override fun importData(c: javax.swing.JComponent, t: java.awt.datatransfer.Transferable): Boolean =
                pasteFromClipboard(t, "TransferHandler") || textHandler.importData(c, t)
            override fun getSourceActions(c: javax.swing.JComponent) = textHandler.getSourceActions(c)
            override fun exportToClipboard(c: javax.swing.JComponent, clip: java.awt.datatransfer.Clipboard, action: Int) = textHandler.exportToClipboard(c, clip, action)
        }
        lineWrap = true
        wrapStyleWord = true
        emptyText.setText("@ pour les fichiers, / pour les commandes, Entrée pour envoyer, Maj+Entrée pour une nouvelle ligne")
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "claude-send")
        actionMap.put("claude-send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { if (!completionKey("accept")) send() }
        })
        inputMap.put(KeyStroke.getKeyStroke("TAB"), "claude-complete")
        actionMap.put("claude-complete", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { if (!completionKey("accept")) transferFocus() }
        })
        for ((key, dir, base) in listOf(Triple("UP", "up", "caret-up"), Triple("DOWN", "down", "caret-down"))) {
            val original = actionMap.get(base)
            inputMap.put(KeyStroke.getKeyStroke(key), "claude-$dir")
            actionMap.put("claude-$dir", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) { if (!completionKey(dir)) original?.actionPerformed(e) }
            })
        }
        inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        inputMap.put(KeyStroke.getKeyStroke("control ENTER"), "claude-refine")
        actionMap.put("claude-refine", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = send(otherBusyMode())
        })
        inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), "claude-stop")
        actionMap.put("claude-stop", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { if (!completionKey("close") && streaming) stop() }
        })
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { mentionChanged(); syncSend() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { mentionChanged(); syncSend() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {}
        })
    }
    private enum class SendMode { QUEUE, REFINE }

    /** Comportement du bouton / d'Entrée pendant une réponse (mémorisé). */
    private var busyMode = if (settings.state.busyMode == "queue") SendMode.QUEUE else SendMode.REFINE
    private fun otherBusyMode() = if (busyMode == SendMode.QUEUE) SendMode.REFINE else SendMode.QUEUE
    private val sendControl = SendControl()

    private fun setBusyMode(m: SendMode) {
        busyMode = m
        settings.state.busyMode = if (m == SendMode.QUEUE) "queue" else "refine"
        syncSend()
    }

    /** Rond orange avec flèche (envoyer) ou carré (interrompre) ; le menu mémorise « file d'attente » ou « préciser ». */
    private fun syncSend() {
        val blank = input.text.isBlank()
        val menu = listOf(
            SplitItem("Mettre en file d'attente", AllIcons.Actions.MoveDown, "Envoyé quand la réponse en cours est terminée", busyMode == SendMode.QUEUE) { setBusyMode(SendMode.QUEUE) },
            SplitItem("Préciser l'exécution en cours", AllIcons.Actions.Lightning, "Injecté dans la réponse en cours, sans l'interrompre", busyMode == SendMode.REFINE) { setBusyMode(SendMode.REFINE) },
        )
        if (streaming && blank) sendControl.configure(RoundSendButton.Mode.STOP, true, "Interrompre la réponse (Échap)", menu) { stop() }
        else {
            val tip = if (streaming) {
                if (busyMode == SendMode.QUEUE) "Mettre en file d'attente (Ctrl+Entrée : préciser)" else "Préciser l'exécution en cours (Ctrl+Entrée : mettre en file d'attente)"
            } else "Envoyer (Entrée)"
            sendControl.configure(RoundSendButton.Mode.SEND, !blank, tip, menu) { send() }
        }
    }

    private val titleLabel = JBLabel().apply { border = JBUI.Borders.emptyLeft(6) }
    private var contextOn = true
    private val extraFiles = mutableListOf<Pair<String, String>>()
    private val extraImages = mutableListOf<Triple<String, String, String>>()
    private val extrasStrip = JPanel(WrapFlowLayout(FlowLayout.LEFT, 4, 2)).apply { isOpaque = false }
    private val thumbCache = java.util.IdentityHashMap<Triple<String, String, String>, javax.swing.Icon>()
    private val extrasRow = JPanel(BorderLayout()).apply {
        isVisible = false
        border = JBUI.Borders.empty(0, 6)
        add(extrasStrip, BorderLayout.CENTER)
        add(ActionLink("effacer").apply { addActionListener { extraFiles.clear(); extraImages.clear(); updateExtras() } }, BorderLayout.EAST)
    }
    private val queueLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { showQueueDetails() }
        })
    }
    private val queueRow = JPanel(BorderLayout()).apply {
        isVisible = false
        border = JBUI.Borders.empty(0, 6)
        add(queueLabel, BorderLayout.CENTER)
        add(ActionLink("vider").apply { addActionListener { queue.clear(); updateQueue() } }, BorderLayout.EAST)
    }
    // Modèle, effort et permissions : état + boutons plats (menus au-dessus de la carte de saisie)
    private var selectedModel = ModelCatalog.resolve(settings.state.model)
    private var selectedEffort = settings.state.effort
    private var selectedMode = settings.state.permissionMode
    private val contextWindows = HashMap<String, Int>()
    private val ring = ContextRing()

    private fun modeLabel() = PERMISSION_MODES.firstOrNull { it.first == selectedMode }?.second ?: selectedMode

    private val plusButton = FlatButton("", AllIcons.General.Add) { showPlusMenu(it) }.apply {
        toolTipText = "Joindre des fichiers ou des images, mentionner un fichier (@) ou une commande (/)"
    }
    private val modeButton = FlatButton(modeLabel(), AllIcons.Nodes.Locked) { showModeMenu(it) }.apply {
        toolTipText = "Permissions : ce que Claude peut faire sans te demander (effectif au message suivant)"
    }
    private val contextChip = FlatButton("", AllIcons.FileTypes.Any_type) { toggleContext() }
    private val modelButton = FlatButton("", null) { showModelMenu(it) }.apply {
        toolTipText = "Modèle et niveau d'effort"
    }

    private fun refreshModelButton() {
        modelButton.text = ModelCatalog.label(selectedModel) + if (selectedEffort != "auto") " · $selectedEffort" else ""
        modelButton.toolTipText = "Modèle : $selectedModel · effort : $selectedEffort (cliquer pour changer)"
    }

    private fun refreshContextChip() {
        val vf = FileEditorManager.getInstance(project).selectedEditor?.file
        contextChip.text = (vf?.name ?: "Aucun fichier").let { if (it.length > 24) it.take(23) + "…" else it }
        contextChip.icon = vf?.fileType?.icon ?: AllIcons.FileTypes.Any_type
        contextChip.dimmed = !contextOn || vf == null
        contextChip.toolTipText = when {
            vf == null -> "Aucun fichier ouvert dans l'éditeur"
            contextOn -> "Contexte : ${vf.path.removePrefix("$cwd/")} sera joint (ou la sélection). Cliquer pour désactiver."
            else -> "Contexte désactivé. Cliquer pour joindre ${vf.name} automatiquement."
        }
    }

    private fun toggleContext() { contextOn = !contextOn; refreshContextChip() }

    private fun saveChoices() {
        settings.state.model = selectedModel; settings.state.effort = selectedEffort; settings.state.permissionMode = selectedMode
    }

    private fun popupOf(group: DefaultActionGroup, anchor: java.awt.Component, right: Boolean = false) {
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataManager.getInstance().getDataContext(anchor), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        )
        showAbove(popup, anchor, right)
    }

    private fun choice(text: String, desc: String?, selected: () -> Boolean, set: () -> Unit) = panelChoice(text, desc, selected, set)

    private fun showModeMenu(anchor: java.awt.Component) {
        val g = DefaultActionGroup()
        PERMISSION_MODES.forEach { (id, label) ->
            g.add(choice(label, PERMISSION_HELP[label], { selectedMode == id }) { selectedMode = id; modeButton.text = modeLabel(); saveChoices() })
        }
        popupOf(g, anchor)
    }

    private fun showModelMenu(anchor: java.awt.Component) {
        val g = DefaultActionGroup()
        g.addSeparator("Modèle")
        (settings.models() + selectedModel).distinct().forEach { id ->
            g.add(choice(ModelCatalog.label(id), id, { selectedModel == id }) { selectedModel = id; refreshModelButton(); saveChoices() })
        }
        g.addSeparator("Effort")
        EFFORTS.forEach { e -> g.add(choice(e, null, { selectedEffort == e }) { selectedEffort = e; refreshModelButton(); saveChoices() }) }
        popupOf(g, anchor, right = true)
    }

    private fun showPlusMenu(anchor: java.awt.Component) {
        val g = DefaultActionGroup()
        g.add(action("Joindre des fichiers ou des images…", AllIcons.General.Add) { addFiles() })
        g.add(action("Mentionner un fichier (@)", AllIcons.FileTypes.Any_type) { input.requestFocusInWindow(); input.insert("@", input.caretPosition) })
        g.add(action("Commandes (/)", AllIcons.Actions.Execute) { input.requestFocusInWindow(); input.insert("/", input.caretPosition) })
        popupOf(g, anchor)
    }

    private val usageLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyRight(6)
        toolTipText = "Tokens cumulés de cette conversation (entrée / sortie)"
    }
    private var usageIn = 0
    private var usageOut = 0
    private var skipAttachOnce = false
    private val slashCommands = mapOf(
        "explain" to "Explique ce code de façon claire : rôle, logique, points délicats.",
        "fix" to "Trouve et corrige les bugs ou erreurs de ce code. Explique brièvement chaque correction.",
        "tests" to "Écris des tests unitaires pertinents pour ce code (cas nominaux, limites, erreurs), avec le framework de test du projet.",
        "doc" to "Ajoute une documentation (commentaires de doc) à ce code sans changer son comportement.",
        "review" to "Fais une revue de code : bugs potentiels, sécurité, lisibilité, performances. Classe les remarques par gravité.",
    )

    // Quota de la session de 5 h : icône batterie cliquable (FR-63) et statut en direct
    private val batteryLabel = JBLabel().apply {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        iconTextGap = JBUI.scale(4)
        border = JBUI.Borders.empty(0, 6)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { showUsageDetails() }
        })
    }
    private val cancelResumeLink = ActionLink("Annuler la reprise").apply {
        isVisible = false
        addActionListener { cancelResume(); session.entries.add(Entry(Kind.NOTICE, "Reprise automatique annulée.")); render() }
    }
    private val statusLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 8)
    }

    // Panneau d'interaction : approbation, plan, question (IDE ou téléphone, le premier gagne)
    private val permText = JBLabel()
    private val permBody = JBTextArea(4, 40).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val permBodyScroll = JBScrollPane(permBody)
    private val permButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
    private val permFree = com.intellij.ui.components.JBTextField().apply {
        emptyText.setText("Réponse libre…")
        addActionListener { sendFree() }
    }
    private val permFreeRow = JPanel(BorderLayout(4, 0)).apply {
        add(permFree, BorderLayout.CENTER)
        add(JButton("Répondre").apply { addActionListener { sendFree() } }, BorderLayout.EAST)
    }
    private val permPanel = JPanel(BorderLayout(0, 4)).apply {
        isVisible = false
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.ORANGE, 1), JBUI.Borders.empty(6))
        add(permText, BorderLayout.NORTH)
        add(permBodyScroll, BorderLayout.CENTER)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(permFreeRow); add(permButtons)
        }, BorderLayout.SOUTH)
    }

    init {
        val group = DefaultActionGroup().apply {
            add(action("Nouvelle conversation dans cet onglet", AllIcons.General.Add) { newSession() })
            add(action("Renommer cet onglet", AllIcons.Actions.Edit) { renameTab() })
            add(action("Sessions (reprendre, renommer, archiver, supprimer)", AllIcons.Vcs.History) { showSessions() })
            add(action("Interrompre", AllIcons.Actions.Suspend, { streaming }) { stop() })
            add(action("Rechercher dans la conversation", AllIcons.Actions.Find) { searchInChat() })
            add(action("Dupliquer la conversation (fork au prochain message)", AllIcons.Actions.Copy, { session.id != null && !streaming }) {
                forkNext = true
                session.entries.add(Entry(Kind.NOTICE, "Fork : le prochain message démarre une nouvelle branche de cette conversation."))
                render(forceBottom = true)
            })
            add(action("Copier la dernière réponse", AllIcons.Actions.Copy) { copyLast() })
            add(toggle("Afficher la réflexion", AllIcons.Actions.Preview, { showThinking }) { showThinking = it; render() })
            add(toggle("Afficher les outils", AllIcons.Toolwindows.ToolWindowHierarchy, { showTools }) { showTools = it; render() })
            add(toggle("Reprendre automatiquement quand le quota est rempli (cet onglet)", AllIcons.Actions.Resume, { autoResumeOn }) {
                autoResumeOn = it
                if (!it) cancelResume()
                render()
            })
            add(toggle("Débloquer à distance depuis le téléphone (cette conversation)", AllIcons.Actions.Share, { remoteOn }) { setRemote(it) })
            add(action("Serveurs MCP", AllIcons.Nodes.Plugin) { showMcpPopup() })
            add(action("Réglages", AllIcons.General.GearPlain) { openSettings() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ClaudeChat", group, true)
        toolbar.targetComponent = this

        val north = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(titleLabel, BorderLayout.CENTER)
        }
        val inputScroll = JBScrollPane(input).apply { border = JBUI.Borders.empty(); viewportBorder = JBUI.Borders.empty() }
        val topRows = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(extrasRow); add(queueRow)
        }
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 6, 6, 6)
            add(composerCard(inputScroll, topRows, input, listOf(plusButton, modeButton, contextChip), listOf(modelButton, ring, sendControl)), BorderLayout.CENTER)
        }
        val splitter = JBSplitter(true, 0.78f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(transcriptCards, BorderLayout.CENTER)
                add(JPanel(BorderLayout()).apply { add(JPanel(BorderLayout()).apply {
                    add(statusLabel, BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { add(cancelResumeLink); add(usageLabel); add(batteryLabel) }, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(permPanel, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
            }
            secondComponent = bottom
        }
        add(north, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        syncSend(); refreshModelButton(); refreshContextChip()
        project.messageBus.connect(parent).subscribe(
            com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) { SwingUtilities.invokeLater { refreshContextChip() } }
            },
        )
        render(forceBottom = true)
        updateUsage()
        updateQuota(); updateStatus()
        if (remoteOn) RemoteBridge.register(phoneSink)
        if (System.currentTimeMillis() - settings.state.rlAt > 30 * 60_000L) probeQuota()
        ApplicationManager.getApplication().executeOnPooledThread { RemoteBridge.ensureStarted() }
        loadProjectFiles()
        // commandes slash de la CLI, chargées dès l'ouverture pour être proposées dès la première saisie
        ApplicationManager.getApplication().executeOnPooledThread {
            val list = CliTurn.listCommands(settings.cliPath(), cwd)
            if (list.isNotEmpty()) cliCommands = list
        }
    }
    @Volatile private var cliCommands: List<CliCommand> = emptyList()

    fun shutdown() { idleTurn?.kill(); idleTurn = null; resumeAt = 0; RemoteBridge.unregister(phoneSink); abandonTurn() }

    // ---------- Barre d'outils ----------

    private fun action(text: String, icon: Icon, enabled: () -> Boolean = { true }, run: () -> Unit) = panelAction(text, icon, enabled, run)

    private fun toggle(text: String, icon: Icon, get: () -> Boolean, set: (Boolean) -> Unit) = panelToggle(text, icon, get, set)

    private fun newSession() {
        abandonTurn()
        cancelResume(); session = Session(); customTitle = null; welcome.shuffle(); resetUsage(); allowAll = false; applyRemote(settings.state.remoteDefault)
        render(forceBottom = true)
        input.requestFocusInWindow()
    }

    fun loadSession(s: Session) {
        abandonTurn()
        cancelResume(); session = s; customTitle = null; allowAll = false; applyRemote(settings.state.remoteDefault)
        resetUsage()
        render(forceBottom = true)
    }

    private fun copyLast() {
        val last = session.entries.lastOrNull { it.kind == Kind.TEXT }?.text ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(last))
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeConfigurable::class.java)
    }

    private fun showSessions() {
        SessionsDialog(project, cwd, session.id) { s, newTab -> if (newTab) ChatToolWindowFactory.openTab(project, s) else loadSession(s) }.show()
    }

    /** Appelé par l'action « Envoyer la sélection ». */
    fun insertSnippet(fileName: String, lang: String, code: String) {
        val prefix = if (input.text.isBlank()) "" else "\n"
        input.append("$prefix${if (fileName.isNotBlank()) "Fichier : $fileName\n" else ""}```$lang\n$code\n```\n")
        input.requestFocusInWindow()
        input.caretPosition = input.document.length
    }

    /** Raccourci des actions « clic droit » : commande + sélection, envoi immédiat. */
    fun ask(command: String, fileName: String, lang: String, code: String) {
        if (streaming) return
        input.text = "/$command\n\n" + (if (fileName.isNotBlank()) "Fichier : $fileName\n" else "") + "```$lang\n$code\n```"
        skipAttachOnce = true
        send()
    }

    // ---------- Envoi ----------

    private fun expandSlash(t: String): String {
        val m = Regex("""^/(\w+)[ \t]*(.*)$""", RegexOption.DOT_MATCHES_ALL).find(t) ?: return t
        val base = slashCommands[m.groupValues[1].lowercase()] ?: return t
        val rest = m.groupValues[2].trim()
        return if (rest.isEmpty()) base else "$base\n\n$rest"
    }

    private fun send(mode: SendMode = busyMode) {
        val original = input.text.trim()
        if (original.isEmpty()) return
        input.text = ""
        if (streaming) {
            if (mode == SendMode.REFINE) refine(original) else { queue.add(original); updateQueue() }
            return
        }
        dispatch(original)
    }

    private val refinedOriginals = HashMap<String, String>()

    /** Précise la consigne en cours : injecté au prochain point d'arrêt de Claude, sans interruption. */
    private fun refine(original: String) {
        val t = turn
        if (t == null || !streaming) { if (streaming) { queue.add(original); updateQueue() } else dispatch(original); return }
        val text = expandSlash(original)
        val body = (mentionAttachments(original).map { it.second } + text).joinToString("\n\n")
        val entry = Entry(Kind.USER, text, note = "⚡ précision envoyée pendant la réponse")
        session.entries.add(entry)
        refinedOriginals[body.trim()] = original
        if (!t.refine(body)) {                       // le tour vient de se terminer : on retombe sur la file
            session.entries.remove(entry); refinedOriginals.remove(body.trim())
            if (streaming) { queue.add(original); updateQueue() } else dispatch(original)
            return
        }
        render(forceBottom = true)
    }

    private fun dispatch(original: String) {
        val text = expandSlash(original)
        val noAttach = skipAttachOnce
        skipAttachOnce = false
        val blocks = mutableListOf<Pair<String, String>>()
        if (contextOn && !noAttach) currentFileAttachment()?.let { blocks.add(it) }
        blocks.addAll(extraFiles)
        blocks.addAll(mentionAttachments(original))
        val files = blocks.distinctBy { it.first }
        val images = extraImages.toList()
        extraFiles.clear(); extraImages.clear(); updateExtras()

        val content = JsonArray()
        images.forEach { (_, media, b64) ->
            content.add(JsonObject().apply {
                addProperty("type", "image")
                add("source", JsonObject().apply { addProperty("type", "base64"); addProperty("media_type", media); addProperty("data", b64) })
            })
        }
        content.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", (files.map { it.second } + text).joinToString("\n\n"))
        })
        val attached = (files.map { it.first } + images.map { it.first }).takeIf { it.isNotEmpty() }?.joinToString(", ")
        session.entries.add(Entry(Kind.USER, text, attached = attached))
        if (session.entries.count { it.kind == Kind.USER } == 1) {
            session.title = original.lineSequence().first { it.isNotBlank() }.take(60)
        }
        startTurn(content)
    }

    private fun currentMode() = selectedMode

    private fun startTurn(content: JsonArray) {
        streaming = true
        interrupted = false
        openKind = null
        val gen = ++generation
        idleTurn?.kill(); idleTurn = null // le nouveau message ouvre son propre processus
        turnStart = System.currentTimeMillis(); liveOut = 0; lastLimits = null
        tick()
        val model = selectedModel.ifBlank { settings.state.model }
        settings.state.model = model
        settings.state.effort = selectedEffort
        settings.state.permissionMode = currentMode()
        render(forceBottom = true)

        val fork = forkNext
        forkNext = false
        val opts = TurnOptions(
            settings.cliPath(), cwd, model, selectedEffort, currentMode(),
            session.id, settings.state.system, emptyList(), fork, waitForMcp = true,
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val mcp = emptyList<McpEntry>()
            val t = CliTurn(TurnOptions(opts.cli, opts.cwd, opts.model, opts.effort, opts.permissionMode, opts.sessionId, opts.appendSystem, mcp, fork, waitForMcp = true), content, listener(gen))
            ApplicationManager.getApplication().invokeLater({ if (gen == generation) turn = t else t.kill() }, ModalityState.any())
            t.start()
        }
    }

    private fun ui(gen: Int, run: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ if (gen == generation) run() }, ModalityState.any())

    private fun listener(gen: Int) = object : TurnListener {
        override fun onInit(sessionId: String, model: String?) = ui(gen) { session.id = sessionId; activeModel = model ?: activeModel; updateStatus() }
        override fun onLiveUsage(contextTokens: Int, outputTokens: Int) = ui(gen) { liveCtx = contextTokens; liveOut = outputTokens; updateStatus() }
        override fun onRateLimits(limits: RateLimits) = ui(gen) { lastLimits = limits; applyLimits(limits) }
        override fun onContextWindow(model: String, tokens: Int) = ui(gen) { contextWindows[model] = tokens; updateRing() }
        override fun onRefineLost(texts: List<String>) = ui(gen) {
            texts.forEach { body ->
                val i = session.entries.indexOfLast { it.kind == Kind.USER && it.note != null }
                if (i >= 0) session.entries.removeAt(i)
                queue.add(0, refinedOriginals.remove(body.trim()) ?: body)
            }
            session.entries.add(Entry(Kind.NOTICE, "Précision arrivée trop tard (réponse presque terminée) : elle est envoyée juste après, comme message empilé."))
            updateQueue()
        }
        override fun onText(delta: String) = ui(gen) { append(Kind.TEXT, delta) }
        override fun onThinking(delta: String) = ui(gen) { append(Kind.THINKING, delta) }
        override fun onToolUse(id: String, name: String, input: JsonObject) = ui(gen) {
            openKind = null
            if (session.entries.none { it.toolId == id }) session.entries.add(Entry(Kind.TOOL, toolId = id, toolName = name, input = input, model = activeModel))
            scheduleRender()
        }
        override fun onToolResult(id: String, text: String, isError: Boolean) = ui(gen) {
            val e = session.entries.firstOrNull { it.toolId == id }
            e?.result = text; e?.isError = isError
            if (e?.toolName?.let { it in WRITE_TOOLS } == true) refreshFiles()
            scheduleRender()
        }
        override fun onPermission(req: PermissionRequest) = ui(gen) {
            if (allowAll && req.tool != "AskUserQuestion" && req.tool != "ExitPlanMode") turn?.allow(req.requestId, req.input)
            else enqueue(req, gen)
        }
        override fun onUsage(input: Int, output: Int, costUsd: Double) = ui(gen) {
            usageIn += input; usageOut += output; this@ChatPanel.costUsd += costUsd; updateUsage()
        }
        override fun onDone(isError: Boolean, message: String?) = ui(gen) { finish(isError, message) }
        override fun onMcpProblem(servers: List<String>) = ui(gen) {
            session.entries.add(Entry(Kind.NOTICE, "Serveur MCP non connecté : ${servers.joinToString(", ")}. Ses outils sont indisponibles pour cette réponse (voir la fenêtre MCP)."))
            scheduleRender()
        }
        override fun onResumed() = ui(gen) {
            if (streaming) return@ui
            streaming = true; interrupted = false; openKind = null
            turn = idleTurn
            turnStart = System.currentTimeMillis(); liveOut = 0
            tick(); syncSend()
        }
    }

    private fun scheduleResume(atMs: Long) {
        resumeAt = atMs; resumeAttempts++
        pollResume()
    }

    /** Vérifie toutes les 30 s (robuste à la mise en veille) si l'heure de reprise est passée. */
    private fun pollResume() {
        if (resumeAt == 0L) return
        if (!streaming && System.currentTimeMillis() >= resumeAt) {
            resumeAt = 0
            val extra = queue.joinToString("\n\n").also { queue.clear(); updateQueue() }
            session.entries.add(Entry(Kind.NOTICE, "Reprise automatique après réinitialisation du quota…"))
            if (remoteOn) RemoteBridge.notifyPhone("▶ [${project.name}] Reprise automatique de la conversation.")
            skipAttachOnce = true
            dispatch("Le quota est réinitialisé : reprends ta tâche là où tu t'étais arrêté." + if (extra.isNotEmpty()) "\n\n$extra" else "")
            return
        }
        updateStatus()
        alarm.addRequest({ pollResume() }, 30_000)
    }

    private fun cancelResume() { resumeAt = 0; resumeAttempts = 0; updateStatus() }

    private fun tick() {
        if (!streaming) return
        updateStatus()
        alarm.addRequest({ tick() }, 1000)
    }

    private fun updateRing() {
        val window = contextWindows[activeModel ?: selectedModel] ?: 200_000
        ring.percent = (liveCtx.toLong() * 100 / window).toInt()
        ring.toolTipText = "Contexte : ${"%,d".format(liveCtx)} / ${"%,d".format(window)} tokens (${ring.percent} %)"
    }

    private fun updateStatus() {
        updateRing()
        val model = activeModel?.let { ModelCatalog.label(it) }
        cancelResumeLink.isVisible = resumeAt > 0
        if (resumeAt > 0) {
            val at = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(resumeAt), java.time.ZoneId.systemDefault())
            val left = Quota.remaining(resumeAt / 1000, System.currentTimeMillis() / 1000)
            statusLabel.text = "⏸ Quota atteint — reprise automatique à ${java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(at)} (dans $left)"
            return
        }
        statusLabel.text = if (streaming) {
            val sec = (System.currentTimeMillis() - turnStart) / 1000
            "⏳ Réponse en cours · %d:%02d · contexte %s · sortie %s tokens".format(sec / 60, sec % 60, fmt(liveCtx), fmt(liveOut)) +
                (model?.let { " · $it" } ?: "")
        } else if (model != null) "$model · contexte ${fmt(liveCtx)} tokens" else ""
        statusLabel.toolTipText = activeModel?.let { "Modèle utilisé : $it" }
    }

    private fun applyLimits(limits: RateLimits) {
        limits.fiveHour?.let { settings.state.rlFiveUtil = it.utilization; settings.state.rlFiveReset = it.resetsAt }
        limits.sevenDay?.let { settings.state.rlWeekUtil = it.utilization; settings.state.rlWeekReset = it.resetsAt }
        settings.state.rlAt = System.currentTimeMillis()
        quotaError = null
        updateQuota()
    }

    /** Mesure les quotas hors conversation (au démarrage, ou via « Actualiser »). */
    private fun probeQuota(onDone: () -> Unit = {}) {
        if (!QuotaProbeGate.tryStart()) return
        quotaError = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = CliTurn.probeQuota(settings.cliPath(), cwd)
            QuotaProbeGate.finish()
            ApplicationManager.getApplication().invokeLater({
                r.limits?.let { applyLimits(it) } ?: run { quotaError = r.error; LOG.warn("Mesure des quotas impossible : ${r.error}") }
                updateQuota(); onDone()
            }, ModalityState.any())
        }
    }

    private fun fiveHourWindow() = settings.state.takeIf { it.rlFiveUtil >= 0 }?.let { QuotaWindow(it.rlFiveUtil, it.rlFiveReset) }
    private fun weekWindow() = settings.state.takeIf { it.rlWeekUtil >= 0 }?.let { QuotaWindow(it.rlWeekUtil, it.rlWeekReset) }

    /** Batterie = part restante de la fenêtre de 5 h ; même icône sur l'onglet. */
    private fun updateQuota() {
        val now = System.currentTimeMillis() / 1000
        val remaining = Quota.remainingPercent(fiveHourWindow(), now)
        val icon = BatteryIcon(remaining)
        batteryLabel.icon = icon
        batteryLabel.text = remaining?.let { "$it %" } ?: "?"
        batteryLabel.toolTipText = (remaining?.let { "Session de 5 h : $it % restant" } ?: "Session de 5 h : pas encore mesurée") + " — cliquer pour le détail"
        content?.icon = icon
    }

    private fun quotaLine(title: String, w: QuotaWindow?, now: Long): String {
        val used = Quota.percent(w, now) ?: return "<b>$title</b> : pas encore mesuré"
        val reset = w!!.resetsAt
        val whenTxt = if (reset > now) {
            val at = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(reset), java.time.ZoneId.systemDefault())
            "réinitialisation dans ${Quota.remaining(reset, now)} (${java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm").format(at)})"
        } else "fenêtre réinitialisée"
        return "<b>$title</b> : $used % utilisé · ${100 - used} % restant<br/><font color='gray'>$whenTxt</font>"
    }

    /** Détail de l'utilisation, au clic sur la batterie. */
    private fun showUsageDetails() {
        val now = System.currentTimeMillis() / 1000
        val st = settings.state
        val measured = if (st.rlAt > 0) "Dernière mesure il y a ${Quota.remaining(now, st.rlAt / 1000)}" else "Aucune mesure pour l'instant"
        val html = "<html><body style='width:340px'>" +
            quotaLine("Session de 5 h", fiveHourWindow(), now) + "<br/><br/>" +
            quotaLine("Semaine (7 jours)", weekWindow(), now) + "<br/><br/>" +
            "<b>Cette conversation</b><br/>" +
            "Modèle : ${activeModel?.let { ModelCatalog.display(it) } ?: ModelCatalog.display(selectedModel)}<br/>" +
            "Contexte : ${fmt(liveCtx)} tokens<br/>" +
            "Tokens cumulés : ↑ ${fmt(usageIn)} entrée · ↓ ${fmt(usageOut)} sortie<br/>" +
            (if (costUsd > 0) "Coût estimé : %.2f $<br/>".format(costUsd) else "") +
            (quotaError?.let { "<br/><font color='red'>Mesure impossible : ${StringUtil.escapeXmlEntities(it)}</font>" } ?: "") +
            (if (noQuotaLastTurn) "<br/><font color='red'>La CLI n'a envoyé aucun quota pendant la dernière réponse.</font>" else "") +
            "<br/><font color='gray'>$measured. Les quotas sont fournis par la CLI après chaque réponse.</font></body></html>"
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JBLabel(html), BorderLayout.CENTER)
            add(JButton("Actualiser").apply {
                toolTipText = "Mesure les quotas maintenant (requête minimale, quelques millièmes de dollar)"
                addActionListener { isEnabled = false; text = "Mesure…"; probeQuota { popupRef?.cancel(); showUsageDetails() } }
            }, BorderLayout.SOUTH)
        }
        popupRef = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
            .setTitle("Utilisation de Claude").setRequestFocus(true).setResizable(false).setMovable(true).setCancelOnClickOutside(true)
            .createPopup().also { it.showUnderneathOf(batteryLabel) }
    }

    private fun append(kind: Kind, delta: String) {
        val last = session.entries.lastOrNull()
        if (openKind == kind && last != null && last.kind == kind) last.text += delta
        else { session.entries.add(Entry(kind, delta, model = activeModel)); openKind = kind }
        scheduleRender()
    }

    private fun finish(isError: Boolean, message: String?) {
        streaming = false
        idleTurn = turn // process gardé ouvert un moment : la CLI peut reprendre (tâche de fond), voir onResumed
        turn = null
        clearInteractions("Réponse terminée", deny = false)
        refinedOriginals.clear()
        val until = if (isError && !interrupted && autoResumeOn && resumeAttempts < MAX_RESUMES)
            QuotaGuard.blockedUntil(lastLimits, message, System.currentTimeMillis() / 1000, settings.state.autoResumeDelayMin * 60L) else null
        if (!isError) resumeAttempts = 0
        noQuotaLastTurn = !isError && !interrupted && lastLimits == null
        if (noQuotaLastTurn) LOG.info("Aucun rate_limit_event reçu pendant cette réponse")
        if (isError) {
            if (interrupted) session.entries.add(Entry(Kind.NOTICE, "Réponse interrompue."))
            else if (until != null) session.entries.add(Entry(Kind.NOTICE, "Quota atteint : la conversation reprendra automatiquement ${settings.state.autoResumeDelayMin} min après le début de la prochaine session."))
            else session.entries.add(Entry(Kind.ERROR, message ?: "Erreur"))
        }
        if (until != null) {
            scheduleResume(until * 1000)
            if (remoteOn) RemoteBridge.notifyPhone("⏸ [${project.name}] Quota atteint, reprise automatique prévue.")
        }
        if (settings.state.notify && !interrupted) Notifier.info(project, "Claude a terminé : ${session.title}")
        if (remoteOn && !interrupted && !isError) {
            val answer = finalAnswer().ifEmpty { "(aucune réponse textuelle)" }
            RemoteBridge.say(phoneSink, "✔ [${project.name} · ${session.title.take(40)}] Claude a terminé :\n\n$answer\n\n↩ Réponds à ce message pour continuer la conversation.")
        } else if (remoteOn && !interrupted && until == null) {
            RemoteBridge.say(phoneSink, "⚠ [${project.name}] Erreur : ${message ?: "inconnue"}")
        }
        render()
        if (queue.isNotEmpty() && resumeAt == 0L) {
            val merged = queue.joinToString("\n\n"); queue.clear(); updateQueue()
            dispatch(merged)
        }
    }

    private fun stop() {
        interrupted = true
        clearInteractions("Interrompu depuis l'IDE", deny = true)
        turn?.interrupt()
    }

    private fun abandonTurn() {
        if (streaming) { generation++; turn?.kill(); turn = null; streaming = false; clearInteractions("Conversation fermée", deny = false) }
    }

    // ---------- Permissions, plans, questions ----------

    private fun enqueue(req: PermissionRequest, gen: Int) {
        val i = when (req.tool) {
            "AskUserQuestion" -> { enqueueQuestions(req, gen); return }
            "ExitPlanMode" -> Interaction(req, "Plan proposé par Claude", req.input["plan"]?.asString ?: "",
                listOf("Accepter le plan", "Refuser"), false) { c ->
                if (c == "Accepter le plan") turn?.allow(req.requestId, req.input) else turn?.deny(req.requestId, "L'utilisateur a refusé le plan.")
            }
            else -> Interaction(req, "${req.tool} — ${toolSummary(req.tool, req.input).take(120)}", describeTool(req.tool, req.input),
                listOf("Autoriser", "Refuser", "Tout autoriser (session)"), false) { c -> decide(req, c) }
        }
        offer(i, gen)
    }

    private fun offer(i: Interaction, gen: Int) {
        pending.add(i)
        if (settings.state.notify) Notifier.info(project, "Claude attend une réponse : ${i.title}")
        if (remoteOn) attachRemote(i, gen)
        nextInteraction()
    }

    private fun attachRemote(i: Interaction, gen: Int) {
        if (i.done || i.handle != null) return
        i.handle = RemoteBridge.offer("[${project.name}] ${i.title}", i.body, i.options, i.freeText) { choice ->
            ui(gen) { finishInteraction(i, choice) }
        }
    }

    private fun applyRemote(on: Boolean) {
        remoteOn = on
        if (on) RemoteBridge.register(phoneSink) else RemoteBridge.unregister(phoneSink)
    }

    /** Message libre reçu du téléphone : devient un message de l'utilisateur (ou attend la fin de la réponse). */
    private fun receivePhone(text: String) {
        if (!remoteOn) return
        if (streaming) {
            queue.add(text); updateQueue()
            RemoteBridge.say(phoneSink, "📨 Claude est encore au travail : ton message est en file d'attente.")
        } else {
            session.entries.add(Entry(Kind.NOTICE, "📱 Message reçu du téléphone."))
            RemoteBridge.say(phoneSink, "▶ Envoyé à Claude.")
            skipAttachOnce = true
            dispatch(text)
        }
    }

    /** Réponse finale à renvoyer au téléphone : le texte après le dernier outil, sinon le dernier texte. */
    private fun finalAnswer(): String {
        val tail = session.entries.takeLastWhile { it.kind != Kind.USER }
        val afterTool = tail.takeLastWhile { it.kind != Kind.TOOL }.filter { it.kind == Kind.TEXT }
        return (afterTool.ifEmpty { tail.filter { it.kind == Kind.TEXT }.takeLast(1) }).joinToString("\n\n") { it.text }.trim()
    }

    /** Active ou coupe l'envoi des demandes de cette conversation vers le téléphone. */
    private fun setRemote(on: Boolean) {
        if (on && !RemoteBridge.isPaired()) {
            Notifier.warn(project, "Accès à distance non prêt : active-le et appaire ton téléphone dans Réglages > Claude Chat.")
            if (ShowSettingsUtil.getInstance().editConfigurable(project, ClaudeConfigurable())) { /* réglages fermés */ }
            if (!RemoteBridge.isPaired()) return
        }
        applyRemote(on)
        val all = listOfNotNull(current) + pending
        if (on) all.forEach { attachRemote(it, generation) }
        else all.forEach { it.handle?.cancel("Accès à distance désactivé pour cette conversation"); it.handle = null }
        updateUsage()
    }

    /** FR-43 : une interaction par question, dans le panneau (et sur le téléphone). */
    private fun enqueueQuestions(req: PermissionRequest, gen: Int) {
        val qs = req.input["questions"]?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val answers = JsonObject()
        fun step(idx: Int) {
            if (idx >= qs.size()) {
                turn?.allow(req.requestId, req.input.deepCopy().apply { add("answers", answers) })
                return
            }
            val qo = qs[idx].asJsonObject
            val question = qo["question"]?.asString ?: return step(idx + 1)
            val opts = qo["options"]?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asJsonObject["label"]?.asString ?: "" } ?: emptyList()
            offer(Interaction(req, qo["header"]?.asString ?: "Question de Claude", question, opts, true) { c ->
                answers.addProperty(question, c); step(idx + 1)
            }, gen)
        }
        step(0)
    }

    private fun nextInteraction() {
        if (current != null) return
        val i = pending.removeFirstOrNull() ?: run { permPanel.isVisible = false; return }
        current = i
        permText.text = "<html><b>${StringUtil.escapeXmlEntities(i.title)}</b></html>"
        permBody.text = i.body; permBody.caretPosition = 0
        permBodyScroll.isVisible = i.body.isNotBlank()
        permButtons.removeAll()
        val canDiff = i.req.tool in EDIT_TOOLS && filePathOf(i.req.input) != null
        if (canDiff) permButtons.add(JButton("Voir le diff").apply { addActionListener { showDiff(i.req) } })
        i.options.forEach { o -> permButtons.add(JButton(o).apply { addActionListener { resolveLocal(i, o) } }) }
        permFreeRow.isVisible = i.freeText
        permFree.text = ""
        permPanel.isVisible = true
        permPanel.revalidate(); permPanel.repaint()
        if (canDiff && settings.state.autoDiff) showDiff(i.req)
    }

    private fun sendFree() { val i = current ?: return; permFree.text.trim().takeIf { it.isNotEmpty() }?.let { resolveLocal(i, it) } }

    private fun resolveLocal(i: Interaction, choice: String) {
        if (i.done) return
        i.handle?.let { if (!it.tryClaim()) return; it.done("l'IDE", choice) }
        finishInteraction(i, choice)
    }

    private fun finishInteraction(i: Interaction, choice: String) {
        if (i.done) return
        i.done = true
        if (current === i) { current = null; permPanel.isVisible = false }
        pending.remove(i)
        i.resolve(choice)
        nextInteraction()
    }

    private fun clearInteractions(reason: String, deny: Boolean) {
        val all = listOfNotNull(current) + pending
        pending.clear(); current = null; permPanel.isVisible = false
        for (i in all) {
            i.done = true
            i.handle?.cancel(reason)
            if (deny) turn?.deny(i.req.requestId, "Interrompu par l'utilisateur")
        }
    }

    /** Applique le choix sur une demande de permission (FR-32 : diff corrigé par l'utilisateur). */
    private fun decide(req: PermissionRequest, choice: String) {
        val edited = editedDocs.remove(req.requestId)?.text
        val prop = proposed.remove(req.requestId)
        val allow = choice != "Refuser"
        if (choice.startsWith("Tout autoriser")) allowAll = true
        if (allow && edited != null && prop != null && edited != prop) {
            val path = filePathOf(req.input)!!
            applyText(path, edited)
            turn?.deny(req.requestId, "L'utilisateur a modifié ta proposition et appliqué lui-même cette version finale du fichier $path. " +
                "Ne refais pas la modification ; relis le fichier si nécessaire.")
        } else if (allow) turn?.allow(req.requestId, req.input)
        else turn?.deny(req.requestId, "L'utilisateur a refusé cette action.")
    }

    // ---------- Diff (FR-30..32) ----------

    private fun filePathOf(input: JsonObject): String? = (input["file_path"] ?: input["path"])?.asString

    private fun showDiff(req: PermissionRequest) {
        val path = filePathOf(req.input) ?: return
        val orig = runCatching { Files.readString(Path.of(path)) }.getOrDefault("")
        val prop = proposedText(req, orig) ?: return
        proposed[req.requestId] = prop
        val ft = FileTypeManager.getInstance().getFileTypeByFileName(Path.of(path).fileName.toString())
        val doc = editedDocs.getOrPut(req.requestId) { EditorFactory.getInstance().createDocument(prop) }
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Claude : $path (la partie droite est modifiable)",
            factory.create(project, orig, ft), factory.create(project, doc, ft),
            "Actuel", "Proposé par Claude",
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun proposedText(req: PermissionRequest, orig: String): String? {
        val i = req.input
        return when (req.tool) {
            "Write" -> i["content"]?.asString
            "Edit" -> applyEdit(orig, i)
            "MultiEdit" -> i["edits"]?.asJsonArray?.fold(orig) { acc, e -> applyEdit(acc, e.asJsonObject) }
            else -> null
        }
    }

    private fun applyEdit(text: String, e: JsonObject): String {
        val old = e["old_string"]?.asString ?: return text
        val new = e["new_string"]?.asString ?: ""
        return if (e["replace_all"]?.asBoolean == true) text.replace(old, new) else text.replaceFirst(old, new)
    }

    private fun applyText(path: String, text: String) {
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                if (vf != null) {
                    FileDocumentManager.getInstance().getDocument(vf)?.let { it.setText(text); FileDocumentManager.getInstance().saveDocument(it) }
                } else {
                    val p = Path.of(path); Files.createDirectories(p.parent); Files.writeString(p, text)
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                }
            }
        }
    }

    /** FR-34 : les fichiers modifiés par Claude sont rechargés dans l'éditeur. */
    private fun refreshFiles() {
        val root = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return
        VfsUtil.markDirtyAndRefresh(true, true, true, root)
    }

    // ---------- Contexte ----------

    private fun updateExtras() {
        extrasRow.isVisible = extraFiles.isNotEmpty() || extraImages.isNotEmpty()
        thumbCache.keys.removeIf { k -> extraImages.none { it === k } }
        extrasStrip.removeAll()
        // une vidéo apparaît une seule fois (miniature de sa 1re image, « clip.mp4 ×6 »)
        val groups = extraImages.groupBy { it.first }
        extraFiles.map { it.first }.distinct().filter { it.substringAfterLast('/') !in groups }.forEach { rel ->
            extrasStrip.add(attachmentChip(AllIcons.FileTypes.Any_type, rel.substringAfterLast('/'), rel, null) { extraFiles.removeAll { it.first == rel }; updateExtras() })
        }
        groups.forEach { (name, list) ->
            extrasStrip.add(attachmentChip(thumbnail(list.first()), if (list.size > 1) "$name ×${list.size}" else name, "$name — cliquer pour agrandir",
                { showImagePreview(list.first()) }) {
                extraImages.removeAll { it.first == name }
                extraFiles.removeAll { it.first.substringAfterLast('/') == name }
                updateExtras()
            })
        }
        extrasStrip.revalidate(); extrasStrip.repaint(); extrasRow.revalidate()
    }

    private fun attachmentChip(icon: Icon?, text: String, tip: String, onClick: (() -> Unit)?, onRemove: () -> Unit): JComponent {
        val hand = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        val label = JBLabel(text, icon, SwingConstants.LEFT).apply {
            toolTipText = tip; iconTextGap = JBUI.scale(5)
            if (onClick != null) {
                cursor = hand
                addMouseListener(object : java.awt.event.MouseAdapter() { override fun mouseClicked(e: java.awt.event.MouseEvent) { onClick() } })
            }
        }
        val remove = JBLabel(AllIcons.Actions.Close).apply {
            toolTipText = "Retirer"; cursor = hand
            addMouseListener(object : java.awt.event.MouseAdapter() { override fun mouseClicked(e: java.awt.event.MouseEvent) { onRemove() } })
        }
        return JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border(), 1, true), JBUI.Borders.empty(2, 4))
            add(label, BorderLayout.CENTER); add(remove, BorderLayout.EAST)
        }
    }

    private fun decodeImage(img: Triple<String, String, String>): java.awt.image.BufferedImage? =
        runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(Base64.getDecoder().decode(img.third))) }.getOrNull()

    private fun scaledIcon(bi: java.awt.image.BufferedImage, maxW: Int, maxH: Int): Icon {
        val k = minOf(maxW.toDouble() / bi.width, maxH.toDouble() / bi.height, 1.0)
        val w = (bi.width * k).toInt().coerceAtLeast(1); val h = (bi.height * k).toInt().coerceAtLeast(1)
        return javax.swing.ImageIcon(bi.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH))
    }

    private fun thumbnail(img: Triple<String, String, String>): Icon? =
        thumbCache[img] ?: decodeImage(img)?.let { scaledIcon(it, JBUI.scale(56), JBUI.scale(40)) }?.also { thumbCache[img] = it }

    private fun showImagePreview(img: Triple<String, String, String>) {
        val bi = decodeImage(img) ?: return
        val label = JBLabel(scaledIcon(bi, JBUI.scale(560), JBUI.scale(420))).apply { border = JBUI.Borders.empty(6) }
        JBPopupFactory.getInstance().createComponentPopupBuilder(label, null)
            .setTitle("${img.first} (${bi.width}×${bi.height})").setRequestFocus(true).setCancelOnClickOutside(true).setMovable(true)
            .createPopup().showInCenterOf(this)
    }

    private fun updateQueue() {
        queueRow.isVisible = queue.isNotEmpty()
        queueLabel.icon = AllIcons.Actions.MoveDown
        queueLabel.text = "<html>En attente : ${esc(queuePreview(queue))}</html>"
        queueLabel.toolTipText = if (queue.size > 1) "Cliquer pour voir et gérer les ${queue.size} messages en attente" else "Cliquer pour voir le message en attente"
        queueRow.revalidate(); queueRow.repaint()
    }

    /** Détail de tous les messages empilés : lecture, ordre, suppression, envoi immédiat en précision. */
    private fun showQueueDetails() {
        if (queue.isEmpty()) return
        val model = CollectionListModel<String>()
        val list = com.intellij.ui.components.JBList(model)
        val detail = JBTextArea(5, 50).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        var popup: com.intellij.openapi.ui.popup.JBPopup? = null
        fun refresh(select: Int) {
            model.replaceAll(queue.mapIndexed { i, m ->
                "${i + 1}. " + m.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(110) + if (m.lines().size > 1) " …" else ""
            })
            if (queue.isEmpty()) { popup?.cancel(); updateQueue(); return }
            list.selectedIndex = select.coerceIn(0, queue.lastIndex)
            updateQueue()
        }
        list.addListSelectionListener { detail.text = queue.getOrNull(list.selectedIndex).orEmpty(); detail.caretPosition = 0 }
        fun btn(label: String, tip: String, run: (Int) -> Unit) = JButton(label).apply {
            toolTipText = tip
            addActionListener { val i = list.selectedIndex; if (i >= 0) run(i) }
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(btn("↑ Monter", "Envoyer plus tôt") { i -> refresh(queue.moveItem(i, i - 1)) })
            add(btn("↓ Descendre", "Envoyer plus tard") { i -> refresh(queue.moveItem(i, i + 1)) })
            add(btn("Supprimer", "Retirer de la file") { i -> queue.removeAt(i); refresh(i) })
            add(btn("Préciser maintenant", "Injecter ce message dans la réponse en cours") { i -> val m = queue.removeAt(i); refresh(i); refine(m) })
        }
        val panel = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(list).apply { preferredSize = java.awt.Dimension(JBUI.scale(520), JBUI.scale(130)) }, BorderLayout.NORTH)
            add(JBScrollPane(detail), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(buttons, BorderLayout.NORTH)
                add(JBLabel("Les messages en attente sont fusionnés et envoyés à la fin de la réponse.").apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, list)
            .setTitle("Messages en attente").setRequestFocus(true).setResizable(true).setMovable(true).setCancelOnClickOutside(true)
            .createPopup()
        refresh(queue.lastIndex)          // par défaut : le dernier empilé est sélectionné et détaillé
        popup.showInCenterOf(this)
    }

    private fun relPath(vf: com.intellij.openapi.vfs.VirtualFile): String {
        val base = project.basePath
        return if (base != null && vf.path.startsWith(base)) vf.path.removePrefix(base).trimStart('/') else vf.name
    }

    private fun addFiles() {
        FileChooser.chooseFiles(FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor(), project, null) { files -> attachVirtualFiles(files) }
    }

    private fun attachVirtualFiles(files: Collection<com.intellij.openapi.vfs.VirtualFile>) {
        run {
            for (vf in files) {
                if (vf.isDirectory) continue
                if (VideoTools.isVideo(vf.name)) { attachVideo(vf.path, relPath(vf), vf.name); continue }
                val ext = vf.extension?.lowercase()
                val media = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp")[ext]
                if (media != null) {
                    extraImages.add(Triple(vf.name, media, Base64.getEncoder().encodeToString(vf.contentsToByteArray())))
                    continue
                }
                if (vf.fileType.isBinary) {
                    Notifier.warn(project, "${vf.name} : fichier binaire non joint (demande à Claude de le lire par son chemin).")
                    continue
                }
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
                if (text.length > 120_000) { Notifier.warn(project, "${vf.name} est trop volumineux : non joint."); continue }
                val rel = relPath(vf)
                extraFiles.add(rel to "<file path=\"$rel\">\n$text\n</file>")
            }
            updateExtras()
        }
    }

    /** Vidéo : quelques images extraites par ffmpeg, en arrière-plan (l'API n'accepte pas la vidéo). */
    private fun attachVideo(path: String, rel: String, name: String) {
        com.intellij.openapi.progress.ProgressManager.getInstance().run(object : com.intellij.openapi.progress.Task.Backgroundable(project, "Extraction des images de « $name »…", true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val (text, frames) = VideoTools.attach(path, rel) { i, n -> indicator.fraction = i.toDouble() / n }
                ApplicationManager.getApplication().invokeLater({
                    extraFiles.add(rel to text)
                    frames.forEach { extraImages.add(Triple(name, "image/jpeg", Base64.getEncoder().encodeToString(it))) }
                    if (frames.isEmpty()) Notifier.warn(project, "$name : images non extraites (ffmpeg requis). Le chemin est transmis à Claude.")
                    else Notifier.info(project, "$name : ${frames.size} image(s) extraite(s) et jointes.")
                    updateExtras()
                }, ModalityState.any())
            }
        })
    }

    /** Fichier ouvert, ou sélection s'il y en a une (FR-20). */
    private fun currentFileAttachment(): Pair<String, String>? {
        val ed = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val vf = FileDocumentManager.getInstance().getFile(ed.document) ?: return null
        val rel = relPath(vf)
        val sel = ed.selectionModel
        if (sel.hasSelection()) {
            val l1 = ed.document.getLineNumber(sel.selectionStart) + 1
            val l2 = ed.document.getLineNumber(sel.selectionEnd) + 1
            return rel to "<selection path=\"$rel\" lines=\"$l1-$l2\">\n${sel.selectedText}\n</selection>"
        }
        val text = ed.document.text
        if (text.length > 120_000) { Notifier.warn(project, "${vf.name} est trop volumineux : non joint."); return null }
        return rel to "<file path=\"$rel\">\n$text\n</file>"
    }

    private fun showMcpPopup() { McpDialog(project, cwd).show() }

    private fun fmt(n: Int) = if (n >= 1000) "%.1fk".format(n / 1000.0) else "$n"

    private fun updateUsage() {
        val parts = mutableListOf<String>()
        if (remoteOn) parts.add("📱")
        if (usageIn + usageOut > 0) parts.add("↑${fmt(usageIn)} ↓${fmt(usageOut)}")
        if (costUsd > 0) parts.add("%.2f $".format(costUsd))
        usageLabel.text = parts.joinToString("  ")
    }

    private fun resetUsage() { usageIn = 0; usageOut = 0; costUsd = 0.0; liveCtx = 0; liveOut = 0; updateUsage(); updateRing() }

    // ---------- Collage d'images, mentions @, recherche ----------

    private var pasteCount = 0

    /**
     * FR-12 : colle une image ou des fichiers copiés comme pièces jointes. Renvoie false si le presse-papiers contient
     * du texte (collage normal) ou rien d'exploitable.
     */
    private fun pasteFromClipboard(t: java.awt.datatransfer.Transferable? = null, via: String = "?"): Boolean {
        val tr = t ?: runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull() ?: return false
        val fileFlavor = java.awt.datatransfer.DataFlavor.javaFileListFlavor
        val textFlavor = java.awt.datatransfer.DataFlavor.stringFlavor
        LOG.info("Collage ($via) : formats du presse-papiers = ${tr.transferDataFlavors.joinToString { it.mimeType.substringBefore(';') + "/" + it.representationClass.simpleName }}")
        if (tr.isDataFlavorSupported(fileFlavor)) {
            val files = (runCatching { tr.getTransferData(fileFlavor) as? List<*> }.getOrNull() ?: emptyList<Any>())
                .filterIsInstance<java.io.File>()
                .mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
            if (files.isNotEmpty()) { attachVirtualFiles(files); return true }
        }
        // du texte copié avec une image (tableur, page web) : c'est le texte qu'on veut coller
        if (tr.isDataFlavorSupported(textFlavor) &&
            runCatching { (tr.getTransferData(textFlavor) as? String).orEmpty().isNotBlank() }.getOrDefault(false)) return false
        val png = clipboardImagePng(tr) ?: return false
        extraImages.add(Triple("image-${++pasteCount}.png", "image/png", Base64.getEncoder().encodeToString(png)))
        updateExtras()
        return true
    }

    /** Image du presse-papiers encodée en PNG : via `imageFlavor`, sinon via un format image (png, jpeg…) lu en flux (Wayland). */
    private fun clipboardImagePng(tr: java.awt.datatransfer.Transferable): ByteArray? {
        fun encode(img: java.awt.Image): ByteArray? {
            val w = img.getWidth(null); val h = img.getHeight(null)
            if (w <= 0 || h <= 0) return null
            val bi = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            bi.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
            return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(bi, "png", it) }.toByteArray()
        }
        if (tr.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
            (runCatching { tr.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as? java.awt.Image }
                .onFailure { LOG.info("Collage : imageFlavor illisible : $it") }.getOrNull())?.let { encode(it)?.let { b -> return b } }
        }
        val streams = tr.transferDataFlavors.filter { it.primaryType == "image" }
            .sortedBy { if (it.subType == "png") 0 else 1 }
        for (f in streams) {
            val data = runCatching { tr.getTransferData(f) }.onFailure { LOG.info("Collage : ${f.mimeType} illisible : $it") }.getOrNull()
            val bytes = (data as? java.io.InputStream)?.use { it.readBytes() }
            val img = when {
                bytes != null -> runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) }.getOrNull()
                data is java.awt.Image -> data
                else -> null
            }
            if (img != null) encode(img)?.let { return it }
        }
        return null
    }

    private var projectFiles: List<String> = emptyList()
    private var mentionPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    private var filesLoadedAt = 0L
    @Volatile private var filesLoading = false

    private fun loadProjectFiles() {
        if (filesLoading) return
        filesLoading = true
        com.intellij.openapi.application.ReadAction.nonBlocking<List<String>> {
            val out = ArrayList<String>()
            val base = project.basePath ?: return@nonBlocking out
            // sous-modules inclus : le contenu du projet couvre tous les modules et dossiers de contenu
            // ignorés : dossiers cachés (.idea, .git…), fichiers ignorés par l'IDE ou le VCS (build, node_modules…)
            val types = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            val vcs = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
            val filter = com.intellij.openapi.vfs.VirtualFileFilter { vf ->
                !(vf.name.startsWith(".") && vf.path != base) && !types.isFileIgnored(vf) && !vcs.isIgnoredFile(vf)
            }
            com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).iterateContent({ vf ->
                if (!vf.isDirectory && out.size < 50_000) out.add(vf.path.removePrefix(base).trimStart('/'))
                true
            }, filter)
            out
        }.finishOnUiThread(ModalityState.any()) {
            projectFiles = it; filesLoadedAt = System.currentTimeMillis(); filesLoading = false
            mentionChanged() // une saisie « @ » faite pendant le chargement obtient enfin ses résultats
        }.submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
    }

    /** Fichiers ouverts puis récemment ouverts, en chemins relatifs au projet et présents dans la liste du projet. */
    private fun recentFiles(): List<String> {
        val base = project.basePath ?: return emptyList()
        val known = projectFiles.toHashSet()
        val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val history = com.intellij.openapi.fileEditor.impl.EditorHistoryManager.getInstance(project).fileList.reversed()
        return (fem.selectedFiles.toList() + fem.openFiles.toList() + history)
            .map { it.path.removePrefix(base).trimStart('/') }
            .filter { it in known }.distinct().take(15)
    }

    private fun mentionChanged() {
        SwingUtilities.invokeLater {
            val slash = SlashToken.activeQuery(input.text, input.caretPosition)
            if (slash != null) { showSlashPopup(slash); return@invokeLater }
            val tok = Mentions.activeToken(input.text, input.caretPosition)
            if (tok == null) { mentionPopup?.cancel(); mentionPopup = null; return@invokeLater }
            // liste préchargée à l'ouverture ; rafraîchie en tâche de fond si elle date (fichiers créés depuis)
            if (projectFiles.isEmpty() || System.currentTimeMillis() - filesLoadedAt > 60_000) loadProjectFiles()
            // sans requête : fichiers ouverts puis récents (plus utile qu'un ordre alphabétique arbitraire)
            val hits = if (tok.second.isEmpty()) recentFiles().ifEmpty { Fuzzy.search("", projectFiles, 15) }
                else Fuzzy.search(tok.second, projectFiles, 15)
            mentionPopup?.cancel()
            if (hits.isEmpty()) return@invokeLater
            val (start, _) = tok
            showCompletion(hits.map { CompletionRow(it) }) { path ->
                val t = input.text
                val end = input.caretPosition.coerceAtMost(t.length)
                input.text = t.substring(0, start) + "@" + path + " " + t.substring(end)
                input.caretPosition = (start + path.length + 2).coerceAtMost(input.document.length)
            }
        }
    }

    /** Ligne du popup de complétion : en-tête de groupe (non sélectionnable) ou choix. */
    private class CompletionRow(val text: String, val header: Boolean = false, val value: String = text)

    private var completionList: com.intellij.ui.components.JBList<CompletionRow>? = null
    private var completionChoose: ((String) -> Unit)? = null

    /** Popup flottant au niveau du curseur ; le focus reste dans le champ, ↑/↓/Entrée/Tab/Échap sont relayés (voir [completionKey]). */
    private fun showCompletion(rows: List<CompletionRow>, onChoose: (String) -> Unit) {
        if (rows.none { !it.header }) return
        val list = com.intellij.ui.components.JBList(rows).apply {
            selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
            selectedIndex = rows.indexOfFirst { !it.header }
            visibleRowCount = rows.size.coerceAtMost(10)
            cellRenderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(l: javax.swing.JList<*>?, v: Any?, i: Int, sel: Boolean, foc: Boolean): java.awt.Component {
                    val row = v as CompletionRow
                    val c = super.getListCellRendererComponent(l, row.text, i, sel && !row.header, false) as javax.swing.JLabel
                    if (row.header) {
                        c.font = c.font.deriveFont(java.awt.Font.BOLD, c.font.size2D - 1f)
                        c.foreground = UIUtil.getContextHelpForeground()
                        c.border = JBUI.Borders.empty(4, 6, 1, 6)
                    } else c.border = JBUI.Borders.empty(1, 14, 1, 6)
                    return c
                }
            }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    val i = locationToIndex(e.point)
                    if (i >= 0 && !rows[i].header) { selectedIndex = i; completionKey("accept") }
                }
            })
        }
        completionList = list
        completionChoose = onChoose
        val pane = JBScrollPane(list).apply { border = JBUI.Borders.empty() }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(pane, null)
            .setRequestFocus(false).setFocusable(false).setResizable(false).setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()
        mentionPopup = popup
        val r = runCatching { input.modelToView2D(input.caretPosition) }.getOrNull()
        val x = r?.x?.toInt() ?: 0
        val top = r?.y?.toInt() ?: 0
        val h = r?.height?.toInt() ?: input.height
        val size = pane.preferredSize
        val belowScreenY = input.locationOnScreen.y + top + h + size.height
        val screenBottom = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.let { it.y + it.height }
        val y = if (belowScreenY > screenBottom) top - size.height - 2 else top + h + 2
        popup.show(com.intellij.ui.awt.RelativePoint(input, java.awt.Point(x, y)))
    }

    /** Relaie une touche au popup de complétion ; renvoie false s'il n'est pas affiché. */
    private fun completionKey(action: String): Boolean {
        val list = completionList
        val popup = mentionPopup
        if (list == null || popup == null || popup.isDisposed || !popup.isVisible) return false
        val n = list.model.size
        fun step(d: Int) {
            var i = list.selectedIndex
            for (k in 0 until n) {
                i = (i + d + n) % n
                if (!list.model.getElementAt(i).header) { list.selectedIndex = i; break }
            }
            // en remontant sur le premier choix, l'en-tête de son groupe reste visible
            list.ensureIndexIsVisible(if (i > 0 && list.model.getElementAt(i - 1).header) i - 1 else i)
        }
        when (action) {
            "up" -> step(-1)
            "down" -> step(1)
            "accept" -> {
                val item = list.selectedValue?.takeIf { !it.header }
                val choose = completionChoose
                popup.cancel(); mentionPopup = null
                if (item != null) choose?.invoke(item.value)
            }
            "close" -> { popup.cancel(); mentionPopup = null }
        }
        return true
    }

    private fun showSlashPopup(query: String) {
        mentionPopup?.cancel(); mentionPopup = null
        val groups = SlashCatalog.group(query, SlashCatalog.build(cliCommands))
        // commande déjà saisie en entier : inutile de la reproposer
        val only = groups.singleOrNull()?.second?.singleOrNull()
        if (groups.isEmpty() || only?.name.equals(query, ignoreCase = true)) return
        val rows = groups.flatMap { (cat, l) ->
            listOf(CompletionRow(cat, header = true)) +
                l.map { CompletionRow("/${it.name}" + if (it.description.isBlank()) "" else " — ${it.description.take(70)}", value = "/${it.name}") }
        }
        showCompletion(rows) { cmd ->
            val end = input.caretPosition.coerceAtMost(input.document.length)
            input.text = "$cmd " + input.text.substring(end)
            input.caretPosition = (cmd.length + 1).coerceAtMost(input.document.length)
        }
    }

    /** Joint les fichiers mentionnés par `@chemin` qui existent dans le projet (FR-13). */
    private fun mentionAttachments(text: String): List<Pair<String, String>> =
        Mentions.find(text).mapNotNull { rel ->
            val vf = LocalFileSystem.getInstance().findFileByPath(if (rel.startsWith("/")) rel else "$cwd/$rel") ?: return@mapNotNull null
            if (vf.isDirectory || vf.fileType.isBinary) return@mapNotNull null
            val t = runCatching { VfsUtilCore.loadText(vf) }.getOrNull()?.takeIf { it.length <= 120_000 } ?: return@mapNotNull null
            rel to "<file path=\"$rel\">\n$t\n</file>"
        }

    private fun searchInChat() {
        val q = Messages.showInputDialog(project, "Rechercher dans la conversation (vide = effacer) :", "Recherche", null, searchQuery, null) ?: return
        searchQuery = q.trim()
        render()
        if (searchQuery.isNotEmpty()) {
            val n = Highlight.count(session.entries.map { it.text }, searchQuery)
            val idx = view.document.getText(0, view.document.length).indexOf(searchQuery, ignoreCase = true)
            if (idx >= 0) SwingUtilities.invokeLater { view.caretPosition = idx; view.modelToView2D(idx)?.let { r -> view.scrollRectToVisible(r.bounds) } }
            titleLabel.text = "${session.title}  —  $n occurrence(s)"
        }
    }

    // ---------- Fichiers cliquables (FR-22) ----------

    private fun openFile(spec: String) {
        val m = Regex("""^(.*?)(?::(\d+))?$""").find(spec) ?: return
        val path = m.groupValues[1]
        val line = m.groupValues[2].toIntOrNull()?.minus(1) ?: 0
        val abs = if (path.startsWith("/")) path else "$cwd/$path"
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(abs) ?: return
        OpenFileDescriptor(project, vf, line.coerceAtLeast(0), 0).navigate(true)
    }

    private val pathRegex = Regex("""<code>([\w@./\\-]+\.[A-Za-z0-9]+)(?::(\d+))?</code>""")

    private fun linkify(html: String): String = pathRegex.replace(html) { m ->
        val p = m.groupValues[1]
        val abs = if (p.startsWith("/")) p else "$cwd/$p"
        if (Files.isRegularFile(Path.of(abs))) {
            val spec = p + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "")
            "<a href=\"open:$spec\"><code>${m.groupValues[1]}${m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ":$it" } ?: ""}</code></a>"
        } else m.value
    }

    // ---------- Rendu ----------

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        alarm.addRequest({ renderPending = false; render() }, 80)
    }

    private fun render(forceBottom: Boolean = false) {
        val bar = scroll.verticalScrollBar
        val atBottom = forceBottom || bar.value + bar.visibleAmount >= bar.maximum - 40
        val oldValue = bar.value
        (transcriptCards.layout as java.awt.CardLayout).show(transcriptCards, if (session.entries.isEmpty()) "welcome" else "chat")
        titleLabel.text = customTitle ?: session.title
        content?.displayName = tabTitle()
        syncSend()
        view.text = Highlight.apply(buildHtml(), searchQuery)
        // FR-08 : ne pas faire sauter le défilement si l'utilisateur est remonté.
        SwingUtilities.invokeLater { bar.value = if (atBottom) bar.maximum else oldValue }
    }

    private fun tabTitle(): String =
        (customTitle ?: session.title).take(24) + (if (current != null) " ❗" else if (streaming) " ⏳" else "")

    private fun renameTab() {
        val t = Messages.showInputDialog(project, "Nom de l'onglet :", "Renommer", null, customTitle ?: session.title, null)?.trim() ?: return
        if (t.isEmpty()) return
        customTitle = t
        session.id?.let { runCatching { SessionStore.rename(it, t) } }
        render()
    }

    private fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)

    private fun buildHtml(): String {
        val font = UIUtil.getLabelFont()
        val codeFont = EditorColorsManager.getInstance().globalScheme.editorFontName
        val fg = UIUtil.getLabelForeground()
        val link = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val userBg = JBColor(Color(0xE8F0FE), Color(0x2D3748))
        val codeBg = JBColor(Color(0xF3F3F3), Color(0x2B2D30))
        val you = JBColor(Color(0x2F6FDE), Color(0x6EA8FE))
        view.background = UIUtil.getEditorPaneBackground()

        val sb = StringBuilder()
        sb.append("<html><head><style>")
        sb.append("body{font-family:'${font.family}';font-size:${font.size}pt;color:${hex(fg)};}")
        sb.append("pre{background-color:${hex(codeBg)};padding:6px;}")
        sb.append("code,pre{font-family:'$codeFont';font-size:${font.size - 1}pt;}")
        sb.append("a{color:${hex(link)};}")
        sb.append("</style></head><body>")
        var lastRole = ""
        for (e in session.entries) {
            when (e.kind) {
                Kind.USER -> {
                    lastRole = "user"
                    sb.append("<table cellpadding='0' cellspacing='0'><tr><td height='8'></td></tr></table>")
                    sb.append("<table width='100%' cellpadding='0' cellspacing='0'><tr><td width='4' bgcolor='${hex(you)}'>&nbsp;</td><td bgcolor='${hex(userBg)}'>")
                    sb.append("<table cellpadding='7' cellspacing='0'><tr><td>")
                    sb.append(speakerLabel("Vous", hex(you), listOfNotNull(e.attached?.let { "joint : $it" }, e.note).joinToString(" · ")))
                    sb.append("<br/>").append(Md.toHtml(e.text)).append("</td></tr></table></td></tr></table>")
                }
                else -> {
                    if (lastRole != "claude" && e.kind != Kind.NOTICE && e.kind != Kind.ERROR) {
                        sb.append(assistantHeader(hex(ACCENT), (e.model ?: activeModel)?.let { ModelCatalog.label(it) }, gap = lastRole != ""))
                        lastRole = "claude"
                    }
                    when (e.kind) {
                        Kind.TEXT -> sb.append(linkify(Md.toHtml(e.text)))
                        Kind.THINKING -> if (showThinking) sb.append("<blockquote><font color='gray'><i>${esc(e.text).replace("\n", "<br/>")}</i></font></blockquote>")
                        Kind.TOOL -> if (showTools) sb.append(toolCard(e, codeBg))
                        Kind.NOTICE -> sb.append("<p><font color='gray'><i>${esc(e.text)}</i></font></p>")
                        Kind.ERROR -> sb.append("<p><font color='red'><b>Erreur :</b> ${esc(e.text)}</font></p>")
                        else -> {}
                    }
                }
            }
        }
        if (streaming && session.entries.lastOrNull()?.kind == Kind.USER) sb.append("<p>…</p>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun toolCard(e: Entry, bg: Color): String {
        val input = e.input ?: JsonObject()
        val status = when { e.result == null -> "⏳"; e.isError -> "✗"; else -> "✓" }
        val summary = toolSummary(e.toolName ?: "?", input)
        val path = filePathOf(input)
        val label = if (path != null) {
            val rel = path.removePrefix("$cwd/")
            "<a href=\"open:${esc(path)}\">${esc(rel)}</a>"
        } else "<code>${esc(summary.take(200))}</code>"
        val res = e.result?.takeIf { it.isNotBlank() && showTools }?.let {
            "<br/><font color='${if (e.isError) "red" else "gray"}' size='-1'>${esc(it.take(500)).replace("\n", "<br/>")}</font>"
        } ?: ""
        return "<table width='100%' cellpadding='4' cellspacing='0'><tr><td bgcolor='${hex(bg)}'>" +
            "<b>$status ${esc(e.toolName ?: "?")}</b> $label$res</td></tr></table>"
    }

    companion object {
        const val MAX_RESUMES = 6
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ChatPanel::class.java)
        val EDIT_TOOLS = setOf("Edit", "Write", "MultiEdit")
        val WRITE_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit", "Bash")
    }
}
