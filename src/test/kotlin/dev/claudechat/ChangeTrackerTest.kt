package dev.claudechat

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Moteur de comparaison de l'IDE, instancié directement (le service d'application n'existe pas dans un test unitaire). */
private val ideStats: (String, String) -> Pair<Int, Int> = { a, b -> LineDiff.stats(a, b, com.intellij.diff.comparison.ComparisonManagerImpl()) }

class ChangeTrackerTest {
    @TempDir lateinit var tmp: Path

    private fun tracker() = ChangeTracker({ tmp.toString() }, stats = ideStats)
    private fun input(path: String) = JsonObject().apply { addProperty("file_path", path) }
    private fun file(name: String, text: String): Path = tmp.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    @Test fun modifiedFileKeepsItsStateFromBeforeTheFirstEdit() {
        val f = file("A.kt", "un\ndeux\ntrois\n")
        val t = tracker()
        t.beforeEdit("Edit", input(f.toString()))
        Files.writeString(f, "un\nDEUX\ntrois\nquatre\n")
        t.beforeEdit("Edit", input(f.toString()))                 // 2e modification : l'état initial est conservé
        Files.writeString(f, "un\nDEUX\ntrois\nquatre\ncinq\n")
        val c = t.changes().single()
        assertEquals(ChangeStatus.MODIFIED, c.status); assertEquals("A.kt", c.rel)
        assertEquals("un\ndeux\ntrois\n", c.before)
        assertEquals(3 to 1, c.added to c.removed, "DEUX, quatre, cinq ajoutés ; deux retiré")
    }

    @Test fun newFileIsReportedAsNewWithItsLineCount() {
        val f = tmp.resolve("sub/Nouveau.kt")
        val t = tracker()
        t.beforeEdit("Write", input(f.toString()))
        Files.createDirectories(f.parent); Files.writeString(f, "a\nb\nc")
        val c = t.changes().single()
        assertEquals(ChangeStatus.NEW, c.status); assertNull(c.before); assertEquals(3, c.added); assertEquals(0, c.removed)
        assertEquals("sub/Nouveau.kt", c.rel)
    }

    @Test fun deletedFileIsReported() {
        val f = file("Z.kt", "x\ny\n")
        val t = tracker(); t.beforeEdit("Edit", input(f.toString()))
        Files.delete(f)
        val c = t.changes().single()
        assertEquals(ChangeStatus.DELETED, c.status); assertEquals(2, c.removed)
    }

    @Test fun unchangedOrRefusedEditsAreNotListed() {
        val f = file("A.kt", "contenu")
        val t = tracker()
        t.beforeEdit("Edit", input(f.toString()))                 // modification refusée : le fichier n'a pas bougé
        t.beforeEdit("Write", input(tmp.resolve("jamais.kt").toString()))   // écriture refusée : le fichier n'existe pas
        assertTrue(t.changes().isEmpty())
        assertFalse(t.isEmpty, "mais ils restent suivis si une modification a lieu ensuite")
    }

    @Test fun otherToolsAndMissingPathsAreIgnored() {
        val t = tracker()
        t.beforeEdit("Read", input(file("A.kt", "x").toString()))
        t.beforeEdit("Bash", JsonObject().apply { addProperty("command", "sed -i s/a/b/ A.kt") })
        t.beforeEdit("Edit", JsonObject())
        assertTrue(t.isEmpty)
    }

    @Test fun relativePathsAndNotebooksAreResolvedAgainstTheProject() {
        val f = file("n.ipynb", "{}")
        val t = tracker()
        t.beforeEdit("NotebookEdit", JsonObject().apply { addProperty("notebook_path", "n.ipynb") })
        Files.writeString(f, "{\"cells\":[]}")
        assertEquals(f.toString(), t.changes().single().path)
    }

    @Test fun unreadableFilesAreNotTracked() {
        val t = ChangeTracker({ tmp.toString() }, read = { throw java.io.IOException("binaire") })
        t.beforeEdit("Edit", input("/x/y.png"))
        assertTrue(t.isEmpty)
    }

    @Test fun clearForgetsEverythingAndRestartsFromTheCurrentState() {
        val f = file("A.kt", "1\n")
        val t = tracker(); t.beforeEdit("Edit", input(f.toString())); Files.writeString(f, "2\n")
        assertEquals(1, t.changes().size)
        t.clear()
        assertTrue(t.changes().isEmpty())
        t.beforeEdit("Edit", input(f.toString())); Files.writeString(f, "3\n")
        assertEquals("2\n", t.changes().single().before, "nouvel état initial = l'état au moment du clear")
    }

    @Test fun bigFilesAreReadAsUnreadable() {
        val f = file("gros.txt", "x".repeat(100))
        assertThrows(java.io.IOException::class.java) { readTextOrNull(f.toString(), maxBytes = 10) }
        assertNull(readTextOrNull(tmp.resolve("absent").toString()))
    }
}

class LineDiffTest {
    @Test fun countsAddedAndRemovedLines() {
        assertEquals(0 to 0, ideStats("a\nb\n", "a\nb\n"))
        assertEquals(1 to 0, ideStats("a\nb\n", "a\nb\nc\n"))
        assertEquals(0 to 1, ideStats("a\nb\nc\n", "a\nc\n"))
        assertEquals(1 to 1, ideStats("a\nb\nc\n", "a\nX\nc\n"))
        assertEquals(3 to 0, ideStats("", "a\nb\nc"))
        assertEquals(0 to 2, ideStats("a\nb", ""))
    }

    @Test fun lineCountIgnoresTheFinalNewline() {
        assertEquals(0, LineDiff.lineCount("")); assertEquals(1, LineDiff.lineCount("a")); assertEquals(1, LineDiff.lineCount("a\n"))
        assertEquals(2, LineDiff.lineCount("a\nb")); assertEquals(3, LineDiff.lineCount("a\n\nb\n"))
    }

    @Test fun staysFastOnLargeFiles() {
        val a = (1..3000).joinToString("\n") { "ligne $it" }
        val b = (1..3000).joinToString("\n") { if (it % 100 == 0) "modifiée $it" else "ligne $it" }
        assertEquals(30 to 30, ideStats(a, b))
    }
}
