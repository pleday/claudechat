package dev.claudechat

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import java.awt.Dimension
import java.util.UUID
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPasswordField
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** Tableau d'édition des serveurs MCP distants (nom, URL, jeton). */
class McpTablePanel {
    private class Row(var id: String, var name: String, var url: String, var enabled: Boolean, var token: String)

    private val rows = mutableListOf<Row>()
    private var snapshot = ""
    private val cols = arrayOf("Actif", "Nom", "URL du serveur MCP", "Jeton (Bearer / OAuth)")

    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getColumnClass(c: Int): Class<*> = if (c == 0) Boolean::class.javaObjectType else String::class.java
        override fun isCellEditable(r: Int, c: Int) = true
        override fun getValueAt(r: Int, c: Int): Any = rows[r].let {
            when (c) { 0 -> it.enabled; 1 -> it.name; 2 -> it.url; else -> it.token }
        }
        override fun setValueAt(v: Any?, r: Int, c: Int) {
            val row = rows[r]
            when (c) {
                0 -> row.enabled = v as Boolean
                1 -> row.name = v as String
                2 -> row.url = v as String
                else -> row.token = v as String
            }
        }
    }

    private val table = JBTable(model).apply {
        columnModel.getColumn(0).apply { preferredWidth = 50; maxWidth = 60 }
        columnModel.getColumn(1).preferredWidth = 120
        columnModel.getColumn(2).preferredWidth = 260
        columnModel.getColumn(3).apply {
            preferredWidth = 140
            cellRenderer = object : DefaultTableCellRenderer() {
                override fun setValue(value: Any?) { text = if ((value as? String).isNullOrEmpty()) "" else "••••••••" }
            }
            cellEditor = DefaultCellEditor(JPasswordField())
        }
    }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            rows.add(Row(UUID.randomUUID().toString(), "", "https://", true, ""))
            model.fireTableRowsInserted(rows.size - 1, rows.size - 1)
            table.setRowSelectionInterval(rows.size - 1, rows.size - 1)
        }
        .setRemoveAction {
            stopEditing()
            val i = table.selectedRow
            if (i >= 0) { rows.removeAt(i); model.fireTableRowsDeleted(i, i) }
        }
        .createPanel()
        .also { it.preferredSize = Dimension(620, 170) }

    private fun stopEditing() { if (table.isEditing) table.cellEditor.stopCellEditing() }

    private fun snap() = rows.joinToString("\n") { "${it.id}|${it.name}|${it.url}|${it.enabled}|${it.token}" }

    fun load(s: ClaudeSettings) {
        rows.clear()
        s.mcpServers().forEach { rows.add(Row(it.id, it.name, it.url, it.enabled, s.mcpToken(it.id) ?: "")) }
        model.fireTableDataChanged()
        snapshot = snap()
    }

    fun isModified(): Boolean { stopEditing(); return snap() != snapshot }

    fun save(s: ClaudeSettings) {
        stopEditing()
        val oldIds = s.mcpServers().map { it.id }.toSet()
        (oldIds - rows.map { it.id }.toSet()).forEach { s.setMcpToken(it, null) }
        rows.forEach { s.setMcpToken(it.id, it.token) }
        s.saveMcpServers(rows.map { McpServer(it.id, it.name.trim(), it.url.trim(), it.enabled) })
        snapshot = snap()
    }
}
