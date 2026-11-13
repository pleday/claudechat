package dev.claudechat

import java.nio.file.Files
import java.nio.file.Path

/**
 * Transforme en liens (`open:chemin`) les chemins de fichiers cités dans le HTML d'une réponse.
 * L'existence des fichiers est mise en cache [ttlMs] : le rendu est refait plusieurs fois par seconde
 * pendant une réponse et ne doit pas toucher le disque à chaque fois (EDT).
 */
class Linkify(
    private val cwd: () -> String,
    private val ttlMs: Long = 5_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val exists: (String) -> Boolean = { runCatching { Files.isRegularFile(Path.of(it)) }.getOrDefault(false) },
) {
    private val cache = HashMap<String, Pair<Long, Boolean>>()

    fun invalidate() = cache.clear()

    private fun abs(p: String) = if (p.startsWith("/")) p else "${cwd()}/$p"

    private fun fileExists(p: String): Boolean {
        val t = now()
        val hit = cache[p]
        if (hit != null && t - hit.first < ttlMs) return hit.second
        if (cache.size > 2_000) cache.clear()
        return exists(abs(p)).also { cache[p] = t to it }
    }

    private val pathRegex = Regex("""<code>([\w@./\\-]+\.[A-Za-z0-9]+)(?::(\d+))?</code>""")

    /** `<code>chemin[:ligne]</code>` devient un lien si le fichier existe. */
    fun files(html: String): String = pathRegex.replace(html) { m ->
        val p = m.groupValues[1]
        if (fileExists(p)) {
            val spec = p + m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ":$it" }.orEmpty()
            "<a href=\"open:$spec\"><code>$spec</code></a>"
        } else m.value
    }

    private val imageRegex = Regex("""(?<![\w/.@-])((?:/|\./|\.\./)?[\w@./-]+\.(?:png|jpe?g|gif|webp|bmp))(?![\w/-])""", RegexOption.IGNORE_CASE)
    private val tagOrText = Regex("""<[^>]*>|[^<]+""")

    /**
     * Chemins d'images en texte libre : liens s'ils existent. Ignore les balises, les liens existants et
     * (sauf [inCode]) le contenu des blocs de code, où un chemin est un simple exemple.
     */
    fun images(html: String, inCode: Boolean = false): String {
        var inLink = false
        var codeDepth = 0
        return tagOrText.replace(html) { m ->
            val t = m.value
            when {
                t.startsWith("<") -> {
                    when {
                        t.startsWith("<a ") || t == "<a>" -> inLink = true
                        t.startsWith("</a") -> inLink = false
                        t.startsWith("<code") || t.startsWith("<pre") -> codeDepth++
                        t.startsWith("</code") || t.startsWith("</pre") -> if (codeDepth > 0) codeDepth--
                    }
                    t
                }
                inLink || (codeDepth > 0 && !inCode) -> t
                else -> imageRegex.replace(t) { im ->
                    val p = im.groupValues[1]
                    if (fileExists(p)) "<a href=\"open:$p\">$p</a>" else im.value
                }
            }
        }
    }
}
