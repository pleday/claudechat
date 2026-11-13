package dev.claudechat

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * La liste des fichiers modifiés ne dépend pas de git : elle se base sur le contenu mémorisé avant chaque modification de
 * Claude et sur l'état du disque. Un commit automatique, un fichier jamais ajouté à git ou ignoré par git n'y changent rien.
 */
class ChangeTrackerGitTest {
    @TempDir lateinit var repo: Path

    private val ideStats: (String, String) -> Pair<Int, Int> = { a, b -> LineDiff.stats(a, b, com.intellij.diff.comparison.ComparisonManagerImpl()) }

    private fun git(vararg args: String): String {
        // hooks désactivés (un hook global peut refuser le message) et configuration locale : dépôt jetable
        val cmd = listOf("git", "-c", "core.hooksPath=/dev/null", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false") + args
        val p = ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "git ${args.toList()} : $out")
        return out
    }

    private fun input(p: Path) = JsonObject().apply { addProperty("file_path", p.toString()) }

    @Test fun autoCommitIgnoredAndUntrackedFilesDoNotChangeTheList() {
        assumeTrue(runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false), "git absent")
        git("init", "-q")
        val a = repo.resolve("A.kt"); Files.writeString(a, "un\ndeux\n")
        Files.writeString(repo.resolve(".gitignore"), "ignore.txt\n")
        git("add", "-A"); git("commit", "-q", "-m", "chore: base")

        val t = ChangeTracker({ repo.toString() }, stats = ideStats)
        // Claude modifie un fichier suivi, crée un fichier jamais ajouté à git, et un fichier ignoré par git
        val created = repo.resolve("Nouveau.kt"); val ignored = repo.resolve("ignore.txt")
        t.beforeEdit("Edit", input(a)); Files.writeString(a, "un\nDEUX\ntrois\n")
        t.beforeEdit("Write", input(created)); Files.writeString(created, "fun x() {}\n")
        t.beforeEdit("Write", input(ignored)); Files.writeString(ignored, "secret\n")

        // 1) git ne voit pas le fichier ignoré, et voit les deux autres comme non validés ; la liste, elle, a les trois
        assertFalse("ignore.txt" in git("status", "--porcelain"))
        assertEquals(setOf("A.kt", "Nouveau.kt", "ignore.txt"), t.changes().map { it.rel }.toSet())

        // 2) commit automatique : git ne voit plus rien (HEAD contient désormais les modifications)
        git("add", "-A"); git("commit", "-q", "-m", "chore: auto")
        assertEquals("", git("status", "--porcelain").trim())
        val after = t.changes().associateBy { it.rel }
        assertEquals(setOf("A.kt", "Nouveau.kt", "ignore.txt"), after.keys, "le commit ne fait pas disparaître les fichiers de la liste")
        assertEquals("un\ndeux\n", after.getValue("A.kt").before, "l'état de comparaison reste celui d'avant Claude, pas celui de HEAD")
        assertEquals(ChangeStatus.NEW, after.getValue("Nouveau.kt").status)
        assertEquals(2 to 1, after.getValue("A.kt").let { it.added to it.removed })
    }
}
