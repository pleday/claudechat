package dev.claudechat

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Photo du projet (taille, date et contenu des petits fichiers texte), prise avant que Claude agisse. Une commande (Bash,
 * sous-agent, outil MCP) peut toucher n'importe quel fichier sans le dire : la photo permet, après coup, de retrouver ce
 * qu'elle a changé et l'état d'avant. Indépendant de git : un commit automatique ou un fichier non versionné n'y change rien.
 *
 * Attribution à Claude : un fichier existant n'est retenu que si sa date de modification tombe dans la fenêtre d'exécution de
 * la commande (une édition faite à la main, ailleurs dans le temps, n'est pas à son compte). Créations, suppressions et
 * renommages (qui conservent la date) se repèrent, eux, par comparaison avec la photo.
 */
class FsBaseline(
    private val root: () -> String,
    private val skipDir: (String) -> Boolean = { false },
    private val skipFile: (String) -> Boolean = { false },
    private val maxFileBytes: Long = Constants.baselineMaxFileBytes,
    private val maxTotalBytes: Long = Constants.baselineMaxTotalBytes,
    private val maxEntries: Int = Constants.baselineMaxEntries,
) {
    data class Stat(val size: Long, val mtime: Long)

    /** [content] null : fichier trop gros, binaire ou hors budget mémoire (on ne pourra pas le comparer). */
    class Entry(val stat: Stat, val content: String?)

    enum class Kind { MODIFIED, NEW, DELETED }

    /** Changement attribué à une commande ; [before] : contenu avant (null pour un fichier créé). */
    class Observed(val path: String, val kind: Kind, val before: String?)

    private class Walk(val files: Map<String, Stat>, val truncated: Boolean)

    @Volatile private var snapshot: Map<String, Entry> = emptyMap()
    @Volatile private var truncated = false

    val fileCount: Int get() = snapshot.size

    private fun walk(): Walk {
        val files = HashMap<String, Stat>()
        var cut = false
        val base = Path.of(root())
        if (!Files.isDirectory(base)) return Walk(files, false)
        Files.walkFileTree(base, emptySet(), Int.MAX_VALUE, object : FileVisitor<Path> {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != base && skipDir(dir.fileName.toString())) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || skipFile(file.fileName.toString())) return FileVisitResult.CONTINUE
                if (files.size >= maxEntries) { cut = true; return FileVisitResult.TERMINATE }
                files[file.toString()] = Stat(attrs.size(), attrs.lastModifiedTime().toMillis())
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
        })
        return Walk(files, cut)
    }

    /** Contenu d'un petit fichier texte (UTF-8 strict, sans octet nul) ; null sinon. */
    private fun readText(path: String, size: Long): String? {
        if (size > maxFileBytes) return null
        return try {
            val bytes = Files.readAllBytes(Path.of(path))
            if (bytes.any { it == 0.toByte() }) return null
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { null } catch (_: IOException) { null }
    }

    /**
     * Prend la photo. Les fichiers dont la taille et la date n'ont pas changé depuis la précédente gardent leur contenu :
     * seuls les fichiers modifiés sont relus. Bloquant (parcours du disque) : à appeler hors du thread de l'interface.
     */
    fun capture() {
        val w = walk()
        val old = snapshot
        var used = 0L
        val out = HashMap<String, Entry>(w.files.size * 2)
        for ((path, st) in w.files) {
            val prev = old[path]
            var content = if (prev != null && prev.stat == st) prev.content else readText(path, st.size)
            if (content != null) { if (used + content.length > maxTotalBytes) content = null else used += content.length }
            out[path] = Entry(st, content)
        }
        snapshot = out
        truncated = w.truncated
    }

    /**
     * Ce qu'une commande exécutée entre [startMillis] et [endMillis] a changé depuis la photo. Vide si la photo manque ou
     * est incomplète (projet trop gros) : mieux vaut ne rien afficher que de prendre tout le projet pour des créations.
     */
    fun scan(startMillis: Long, endMillis: Long, slackMs: Long = 1_500): List<Observed> {
        val base = snapshot
        if (base.isEmpty() || truncated) return emptyList()
        val now = walk()
        val out = ArrayList<Observed>()
        val from = startMillis - slackMs; val to = endMillis + slackMs
        for ((path, st) in now.files) {
            val e = base[path]
            when {
                e == null -> out.add(Observed(path, Kind.NEW, null))
                e.stat != st && st.mtime in from..to -> e.content?.let { out.add(Observed(path, Kind.MODIFIED, it)) }
            }
        }
        if (!now.truncated) for ((path, e) in base) if (path !in now.files) e.content?.let { out.add(Observed(path, Kind.DELETED, it)) }
        return out
    }
}
