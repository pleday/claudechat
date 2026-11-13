package dev.claudechat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.text.DateFormatUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Liste paginée des sessions de la CLI : reprendre, renommer, archiver, supprimer (FR-50, FR-52). */
class SessionsDialog(
    private val project: Project,
    private val cwd: String,
    private val currentId: String?,
    private val onOpen: (Session, Boolean) -> Unit,
) : DialogWrapper(project) {
    private val all = mutableListOf<SessionInfo>()
    private val model = CollectionListModel<SessionInfo>()
    private val list = JBList(model)
    private val search = SearchTextField()
    private val archived = JBCheckBox("Archivées")
    private var exhausted = false
    private var newTab = false
    private val page = 30

    init {
        title = "Sessions Claude"
        setOKButtonText("Ouvrir")
        list.cellRenderer = object : ColoredListCellRenderer<SessionInfo>() {
            override fun customizeCellRenderer(l: JList<out SessionInfo>, v: SessionInfo?, i: Int, sel: Boolean, focus: Boolean) {
                v ?: return
                append(v.title, if (v.id == currentId) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("   " + DateFormatUtil.formatPrettyDateTime(v.updated), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (v.archived) append("  (archivée)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) doOKAction() }
        })
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = filter()
            override fun removeUpdate(e: DocumentEvent?) = filter()
            override fun changedUpdate(e: DocumentEvent?) = filter()
        })
        archived.addActionListener { reload() }
        init()
        reload()
    }

    private fun filter() {
        val q = search.text.trim().lowercase()
        model.replaceAll(all.filter { q.isEmpty() || it.title.lowercase().contains(q) })
    }

    private fun reload() { all.clear(); exhausted = false; loadMore() }

    private fun loadMore() {
        if (exhausted) return
        val offset = all.size
        val withArchived = archived.isSelected
        ApplicationManager.getApplication().executeOnPooledThread {
            val got = runCatching { SessionStore.list(cwd, offset, page, withArchived) }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (got.size < page) exhausted = true
                all.addAll(got); filter()
            }, ModalityState.any())
        }
    }

    private fun selected() = list.selectedValue

    override fun createCenterPanel(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JButton("Renommer").apply { addActionListener {
                val s = selected() ?: return@addActionListener
                Messages.showInputDialog(project, "Nouveau titre :", "Renommer", null, s.title, null)?.takeIf { it.isNotBlank() }?.let {
                    SessionStore.rename(s.id, it); reload()
                }
            } })
            add(JButton("Archiver / restaurer").apply { addActionListener {
                selected()?.let { SessionStore.setArchived(it.id, !it.archived); reload() }
            } })
            add(JButton("Supprimer").apply { addActionListener {
                val s = selected() ?: return@addActionListener
                if (Messages.showYesNoDialog(project, "Supprimer définitivement « ${s.title} » ?", "Supprimer", null) == Messages.YES) {
                    SessionStore.delete(s); reload()
                }
            } })
            add(JButton("Ouvrir dans un nouvel onglet").apply { addActionListener { newTab = true; doOKAction() } })
            add(JButton("Plus…").apply { addActionListener { loadMore() } })
            add(archived)
        }
        return JPanel(BorderLayout(0, 4)).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
            preferredSize = Dimension(640, 420)
        }
    }

    override fun doOKAction() {
        val info = selected() ?: run { newTab = false; return }
        val openInTab = newTab
        super.doOKAction()
        ApplicationManager.getApplication().executeOnPooledThread {
            val s = runCatching { SessionStore.load(info) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ onOpen(s, openInTab) }, ModalityState.any())
        }
    }
}
