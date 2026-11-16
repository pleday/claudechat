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
    /** Dimensions (largeur, hauteur) de l'image au chemin absolu donné, sans la décoder ; null si illisible. */
    private val imageSize: (String) -> Pair<Int, Int>? = ::readImageSize,
) {
    private val cache = HashMap<String, Pair<Long, Boolean>>()
    private val sizes = HashMap<String, Pair<Int, Int>?>()

    private var invalidations = 0

    fun invalidate() { cache.clear(); sizes.clear(); invalidations++ }

    /** Change quand le résultat de [files] / [images] peut avoir changé (invalidation, ou changement de fenêtre de 6 × la durée du cache d'existence). */
    fun stamp(): Pair<Int, Long> = invalidations to now() / (ttlMs * 6)

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
                    if (fileExists(p)) thumbnail(p) else im.value
                }
            }
        }
    }

    /** Miniature cliquable (ouvre l'image) ; simple lien si les dimensions de l'image sont illisibles. */
    private fun thumbnail(p: String): String {
        val path = abs(p)
        if (sizes.size > 500) sizes.clear()
        val size = sizes.getOrPut(path) { imageSize(path) }
        if (size == null || size.first <= 0 || size.second <= 0) return "<a href=\"open:$p\">$p</a>"
        val k = minOf(THUMB_W.toDouble() / size.first, THUMB_H.toDouble() / size.second, 1.0)
        val w = (size.first * k).toInt().coerceAtLeast(1); val h = (size.second * k).toInt().coerceAtLeast(1)
        val src = java.io.File(path).toURI()
        return "<a href=\"open:$p\"><img src=\"$src\" width=\"$w\" height=\"$h\" alt=\"$p\" title=\"$p\" border=\"0\"></a>"
    }

    companion object {
        const val THUMB_W = 220
        const val THUMB_H = 160

        fun readImageSize(path: String): Pair<Int, Int>? = runCatching {
            javax.imageio.ImageIO.createImageInputStream(java.io.File(path)).use { ins ->
                val readers = javax.imageio.ImageIO.getImageReaders(ins)
                if (!readers.hasNext()) return@use null
                val r = readers.next()
                try { r.input = ins; r.getWidth(0) to r.getHeight(0) } finally { r.dispose() }
            }
        }.getOrNull()
    }
}
