package dev.claudechat

import com.google.gson.JsonObject
import com.intellij.openapi.util.text.StringUtil
import java.awt.Color

/** Chemin de fichier visé par l'entrée d'un outil (`file_path` ou `path`). */
fun filePathOf(input: JsonObject): String? = (input["file_path"] ?: input["path"])?.asString

/**
 * Construit le HTML de la conversation (Swing HTMLEditorKit) à partir des entrées. Sans état Swing ni IDE :
 * les couleurs et polices viennent de [Theme], le résultat est testable. Les entrées inchangées sont réutilisées
 * (voir [RenderCache]), seule la dernière est recalculée pendant le streaming.
 */
class TranscriptRenderer(private val cwd: () -> String) {
    class Theme(
        val fontFamily: String, val fontSize: Int, val codeFont: String,
        val fg: Color, val link: Color, val userBg: Color, val codeBg: Color, val you: Color, val accent: Color,
    )

    class Options(val showThinking: Boolean, val showTools: Boolean, val activeModel: String?, val streaming: Boolean)

    /** [codeTexts] : texte brut des blocs copiables, indexés comme les ids `code<n>` du HTML. */
    class Result(val html: String, val codeTexts: List<String>)

    val linker = Linkify(cwd)
    private val renders = RenderCache()

    private fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)

    fun build(entries: List<Entry>, o: Options, t: Theme): Result {
        val sb = StringBuilder()
        sb.append("<html><head><style>")
        sb.append("body{font-family:'${t.fontFamily}';font-size:${t.fontSize}pt;color:${hex(t.fg)};}")
        sb.append("td.cb{background-color:${hex(t.codeBg)};padding:6px;}")
        sb.append("pre{background-color:${hex(t.codeBg)};padding:6px;}")
        sb.append("code,pre{font-family:'${t.codeFont}';font-size:${t.fontSize - 1}pt;}")
        sb.append("a{color:${hex(t.link)};}")
        sb.append("</style></head><body>")
        var lastRole = ""
        val codeTexts = ArrayList<String>()
        renders.retainOnly(entries)
        var responses = 0
        var respOpen = false
        for (e in entries) {
            if (e.kind == Kind.USER && respOpen) { sb.append("</div>"); respOpen = false }
            when (e.kind) {
                Kind.USER -> {
                    lastRole = "user"
                    sb.append("<table cellpadding='0' cellspacing='0'><tr><td height='8'></td></tr></table>")
                    sb.append("<table width='100%' cellpadding='0' cellspacing='0'><tr><td width='4' bgcolor='${hex(t.you)}'>&nbsp;</td><td bgcolor='${hex(t.userBg)}'>")
                    sb.append("<table cellpadding='7' cellspacing='0'><tr><td>")
                    sb.append(speakerLabel("Vous", hex(t.you), listOfNotNull(e.attached?.let { "joint : $it" }, e.note).joinToString(" · ")))
                    sb.append("<br/>").append(renders.render(e, e.text) { Md.toHtml(e.text) }).append("</td></tr></table></td></tr></table>")
                }
                else -> {
                    if (lastRole != "claude" && e.kind != Kind.NOTICE && e.kind != Kind.ERROR) {
                        sb.append("<div id='resp${responses++}'>"); respOpen = true
                        sb.append(assistantHeader(hex(t.accent), (e.model ?: o.activeModel)?.let { ModelCatalog.label(it) }, gap = lastRole != ""))
                        lastRole = "claude"
                    }
                    when (e.kind) {
                        Kind.TEXT -> sb.append(renders.render(e, e.text to linker.stamp(), codeTexts) { c -> linker.images(linker.files(Md.toHtml(e.text, c))) })
                        Kind.THINKING -> if (o.showThinking) sb.append("<blockquote><font color='gray'><i>${esc(e.text).replace("\n", "<br/>")}</i></font></blockquote>")
                        Kind.TOOL -> if (o.showTools) sb.append(renders.render(e, listOf(e.result, e.isError, t.codeBg.rgb, linker.stamp())) { toolCard(e, t.codeBg) })
                        Kind.NOTICE -> sb.append("<p><font color='gray'><i>${esc(e.text)}</i></font></p>")
                        Kind.ERROR -> sb.append("<p><font color='red'><b>Erreur :</b> ${esc(e.text)}</font></p>")
                        else -> {}
                    }
                }
            }
        }
        if (respOpen) sb.append("</div>")
        if (o.streaming && entries.lastOrNull()?.kind == Kind.USER) sb.append("<p>…</p>")
        sb.append("</body></html>")
        return Result(sb.toString(), codeTexts)
    }

    private fun toolCard(e: Entry, bg: Color): String {
        val input = e.input ?: JsonObject()
        val status = when { e.result == null -> "⏳"; e.isError -> "✗"; else -> "✓" }
        val summary = toolSummary(e.toolName ?: "?", input)
        val path = filePathOf(input)
        val label = if (path != null) {
            val rel = path.removePrefix("${cwd()}/")
            "<a href=\"open:${esc(path)}\">${esc(rel)}</a>"
        } else linker.images("<code>${esc(summary.take(200))}</code>", inCode = true)
        val res = e.result?.takeIf { it.isNotBlank() }?.let {
            "<br/><font color='${if (e.isError) "red" else "gray"}' size='-1'>${esc(it.take(500)).replace("\n", "<br/>")}</font>"
        } ?: ""
        return "<table width='100%' cellpadding='4' cellspacing='0'><tr><td bgcolor='${hex(bg)}'>" +
            "<b>$status ${esc(e.toolName ?: "?")}</b> $label$res</td></tr></table>"
    }
}
