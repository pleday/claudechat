package dev.claudechat

import com.google.gson.JsonObject
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.DumbProgressIndicator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

enum class ChangeStatus { MODIFIED, NEW, DELETED }

/** Un fichier modifié par Claude : [before] est son contenu avant la première modification (null : le fichier n'existait pas). */
data class ChangedFile(
    val path: String, val rel: String, val status: ChangeStatus, val added: Int, val removed: Int, val before: String?,
)

/** Contenu d'un fichier texte ; null s'il n'existe pas ; IOException s'il est trop gros ou illisible comme du texte (binaire). */
fun readTextOrNull(path: String, maxBytes: Long = 2L * 1024 * 1024): String? {
    val p = Path.of(path)
    if (!Files.isRegularFile(p)) return null
    if (Files.size(p) > maxBytes) throw IOException("fichier trop volumineux")
    return Files.readString(p)
}

/**
 * Fichiers modifiés par Claude pendant la conversation. Le contenu d'un fichier est mémorisé juste avant le premier
 * outil qui le modifie (Edit, Write, MultiEdit, NotebookEdit) : la comparaison montre ainsi tout ce que Claude a changé
 * depuis le début de la conversation, et pas seulement la dernière modification. L'état actuel est relu sur le disque.
 *
 * Les fichiers modifiés par des commandes (Bash : sed, git…) ne sont pas suivis : on ne sait pas lesquels avant coup.
 */
class ChangeTracker(
    private val cwd: () -> String,
    private val read: (String) -> String? = ::readTextOrNull,
    private val stats: (String, String) -> Pair<Int, Int> = { a, b -> LineDiff.stats(a, b) },
) {
    private val before = LinkedHashMap<String, String?>()

    private fun abs(raw: String): String =
        runCatching { Path.of(if (raw.startsWith("/")) raw else "${cwd()}/$raw").normalize().toString() }.getOrDefault(raw)

    /** À appeler dès que Claude annonce un outil, avant qu'il s'exécute. Sans effet pour les autres outils et les fichiers déjà suivis. */
    fun beforeEdit(tool: String, input: JsonObject) {
        if (tool !in TOOLS) return
        val raw = (input["file_path"] ?: input["notebook_path"] ?: input["path"])?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val path = abs(raw)
        synchronized(this) { if (path in before) return }
        // lecture hors verrou ; fichier illisible (binaire, trop gros) : non suivi
        val old = try { read(path) } catch (_: Exception) { return }
        synchronized(this) { if (path !in before) before[path] = old }
    }

    val isEmpty: Boolean get() = synchronized(this) { before.isEmpty() }

    /** Oublie tout : les prochaines modifications repartent de l'état du disque à ce moment. */
    fun clear() = synchronized(this) { before.clear() }

    /** Fichiers réellement différents de leur état initial (une modification refusée ou annulée n'apparaît pas). */
    fun changes(): List<ChangedFile> {
        val snapshot = synchronized(this) { before.toMap() }
        val base = cwd()
        return snapshot.mapNotNull { (path, old) ->
            val now = try { read(path) } catch (_: Exception) { return@mapNotNull null }
            val rel = path.removePrefix("$base/")
            when {
                old == null && now == null -> null
                old == null -> ChangedFile(path, rel, ChangeStatus.NEW, LineDiff.lineCount(now!!), 0, null)
                now == null -> ChangedFile(path, rel, ChangeStatus.DELETED, 0, LineDiff.lineCount(old), old)
                old == now -> null
                else -> stats(old, now).let { (a, r) -> ChangedFile(path, rel, ChangeStatus.MODIFIED, a, r, old) }
            }
        }
    }

    companion object {
        val TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")
    }
}

/** Décompte des lignes ajoutées et retirées, par le moteur de comparaison de l'IDE : mêmes chiffres que l'outil de comparaison. */
object LineDiff {
    fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + (if (text.endsWith('\n')) 0 else 1)

    /** (lignes ajoutées, lignes retirées) de [old] vers [new]. */
    fun stats(old: String, new: String, manager: ComparisonManager = ComparisonManager.getInstance()): Pair<Int, Int> {
        // pour le moteur, un texte vide est une ligne vide : un fichier vidé ou rempli depuis rien est compté à part
        if (old.isEmpty()) return lineCount(new) to 0
        if (new.isEmpty()) return 0 to lineCount(old)
        val fragments = manager.compareLines(old, new, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
        return fragments.sumOf { it.endLine2 - it.startLine2 } to fragments.sumOf { it.endLine1 - it.startLine1 }
    }
}
