package dev.claudechat

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object Md {
    private val exts = listOf(TablesExtension.create(), AutolinkExtension.create())
    private val parser = Parser.builder().extensions(exts).build()
    private val renderer = HtmlRenderer.builder().extensions(exts).escapeHtml(true).sanitizeUrls(true).build()

    private val fileFence = Regex("""(?m)^```([^\s`:]*)[ \t]*file:(\S+).*$""")

    private val preBlock = Regex("""<pre><code[^>]*>(.*?)</code></pre>""", RegexOption.DOT_MATCHES_ALL)

    private val quoteBlock = Regex("""<blockquote>(.*?)</blockquote>""", RegexOption.DOT_MATCHES_ALL)

    private fun plainText(html: String): String =
        unescape(html.replace("</p>", "\n\n").replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")).trim()

    private fun unescape(h: String) = h.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    /** Swing ne sait pas replier <pre> : on le remplace par des lignes qui passent à la ligne (indentation conservée) pour éviter l'ascenseur horizontal. */
    private fun wrapCode(body: String, id: String? = null): String {
        val lines = body.removeSuffix("\n").split("\n").joinToString("<br>") { l ->
            val indent = l.takeWhile { it == ' ' || it == '\t' }
            indent.replace("\t", "    ").replace(" ", "&nbsp;") + l.substring(indent.length)
        }
        return "<table ${id?.let { "id='$it' " } ?: ""}width='100%' cellpadding='0' cellspacing='0'><tr><td class='cb'><code>$lines</code></td></tr></table>"
    }

    /** [codes] : si fourni, chaque bloc de code reçoit l'id `code<n>` et son texte brut y est ajouté (bouton « copier »). */
    fun toHtml(md: String, codes: MutableList<String>? = null): String =
        renderer.render(parser.parse(md.replace(fileFence) { "**Fichier : ${it.groupValues[2]}**\n\n```${it.groupValues[1]}" }))
            .replace(preBlock) { m ->
                val id = codes?.let { it.add(unescape(m.groupValues[1]).removeSuffix("\n")); "code${it.size - 1}" }
                wrapCode(m.groupValues[1], id)
            }
            .replace(quoteBlock) { m ->
                val id = codes?.let { it.add(plainText(m.groupValues[1])); "code${it.size - 1}" }
                "<blockquote${id?.let { " id='$it'" } ?: ""}>${m.groupValues[1]}</blockquote>"
            }
            .replace("<table>", "<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\">")
}
