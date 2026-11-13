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

    fun toHtml(md: String): String =
        renderer.render(parser.parse(md.replace(fileFence) { "**Fichier : ${it.groupValues[2]}**\n\n```${it.groupValues[1]}" }))
            .replace("<table>", "<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\">")
}
