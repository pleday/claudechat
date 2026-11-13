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

    /** Swing ne sait pas replier <pre> : on le remplace par des lignes qui passent à la ligne (indentation conservée) pour éviter l'ascenseur horizontal. */
    private fun wrapCode(body: String): String {
        val lines = body.removeSuffix("\n").split("\n").joinToString("<br>") { l ->
            val indent = l.takeWhile { it == ' ' || it == '\t' }
            indent.replace("\t", "    ").replace(" ", "&nbsp;") + l.substring(indent.length)
        }
        return "<table width='100%' cellpadding='0' cellspacing='0'><tr><td class='cb'><code>$lines</code></td></tr></table>"
    }

    fun toHtml(md: String): String =
        renderer.render(parser.parse(md.replace(fileFence) { "**Fichier : ${it.groupValues[2]}**\n\n```${it.groupValues[1]}" }))
            .replace(preBlock) { wrapCode(it.groupValues[1]) }
            .replace("<table>", "<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\">")
}
