package dev.claudechat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class FsBaselineTest {
    @TempDir lateinit var tmp: Path

    // Fenêtre d'exécution d'une commande fictive : [T0, T1] ; les dates des fichiers sont posées explicitement
    private val T0 = 1_800_000_000_000L
    private val T1 = T0 + 5_000
    private val OLD = T0 - 3_600_000     // bien avant la fenêtre
    private val INSIDE = T0 + 2_000

    private fun base(
        skipDir: (String) -> Boolean = { it == "build" || it == ".git" },
        skipFile: (String) -> Boolean = { false },
        maxEntries: Int = 60_000, maxTotal: Long = 24L * 1024 * 1024, maxFile: Long = 256 * 1024,
    ) = FsBaseline({ tmp.toString() }, skipDir, skipFile, maxFile, maxTotal, maxEntries)

    private fun put(rel: String, text: String, mtime: Long = OLD): Path {
        val f = tmp.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text)
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime)); return f
    }

    private fun kinds(l: List<FsBaseline.Observed>) = l.associate { tmp.relativize(Path.of(it.path)).toString() to it.kind }

    @Test fun fileModifiedDuringTheCommandIsReportedWithItsPreviousContent() {
        val f = put("A.kt", "avant")
        val b = base(); b.capture()
        Files.writeString(f, "après!"); Files.setLastModifiedTime(f, FileTime.fromMillis(INSIDE))
        val o = b.scan(T0, T1).single()
        assertEquals(FsBaseline.Kind.MODIFIED, o.kind); assertEquals("avant", o.before)
    }

    @Test fun editsMadeOutsideTheCommandWindowAreNotAttributedToClaude() {
        val f = put("A.kt", "avant")
        val b = base(); b.capture()
        // l'utilisateur enregistre le fichier bien après la fin de la commande, ou bien avant son début
        Files.writeString(f, "modif utilisateur"); Files.setLastModifiedTime(f, FileTime.fromMillis(T1 + 60_000))
        assertTrue(b.scan(T0, T1).isEmpty())
        Files.writeString(f, "autre modif"); Files.setLastModifiedTime(f, FileTime.fromMillis(T0 - 60_000))
        assertTrue(b.scan(T0, T1).isEmpty())
    }

    @Test fun createdAndDeletedFilesAreReported() {
        put("gone.txt", "bye"); put("keep.txt", "k")
        val b = base(); b.capture()
        put("new.txt", "hello", INSIDE); Files.delete(tmp.resolve("gone.txt"))
        val k = kinds(b.scan(T0, T1))
        assertEquals(mapOf("new.txt" to FsBaseline.Kind.NEW, "gone.txt" to FsBaseline.Kind.DELETED), k)
        assertEquals("bye", b.scan(T0, T1).first { it.kind == FsBaseline.Kind.DELETED }.before)
    }

    @Test fun renameKeepsTheDateButIsStillSeenAsDeletePlusCreate() {
        val f = put("NOTES.md", "contenu")          // date ancienne : `mv` la conserve
        val b = base(); b.capture()
        Files.move(f, tmp.resolve("LISEZMOI.md"))
        assertEquals(mapOf("LISEZMOI.md" to FsBaseline.Kind.NEW, "NOTES.md" to FsBaseline.Kind.DELETED), kinds(b.scan(T0, T1)))
    }

    @Test fun skippedFoldersAndFilesAreIgnored() {
        put("src/A.kt", "a"); val b = base(skipFile = { it.endsWith(".log") }); b.capture()
        put("build/out.class", "x", INSIDE); put(".git/index", "x", INSIDE); put("run.log", "x", INSIDE); put("src/B.kt", "b", INSIDE)
        assertEquals(mapOf("src/B.kt" to FsBaseline.Kind.NEW), kinds(b.scan(T0, T1)))
    }

    @Test fun binaryAndOversizedFilesCannotBeComparedSoModifiedOnesAreSkipped() {
        val bin = tmp.resolve("img.png"); Files.write(bin, byteArrayOf(1, 0, 2, 3)); Files.setLastModifiedTime(bin, FileTime.fromMillis(OLD))
        val big = put("gros.txt", "x".repeat(100))
        val b = base(maxFile = 50); b.capture()
        Files.write(bin, byteArrayOf(9, 0, 9)); Files.setLastModifiedTime(bin, FileTime.fromMillis(INSIDE))
        Files.writeString(big, "y".repeat(100)); Files.setLastModifiedTime(big, FileTime.fromMillis(INSIDE))
        assertTrue(b.scan(T0, T1).isEmpty(), "pas d'état d'avant : rien à comparer")
    }

    @Test fun totalMemoryBudgetIsRespected() {
        put("a.txt", "x".repeat(60)); put("b.txt", "y".repeat(60))
        val b = base(maxTotal = 100); b.capture()
        // un seul des deux a gardé son contenu ; l'autre, modifié, n'est pas comparable
        for (n in listOf("a.txt", "b.txt")) { val f = tmp.resolve(n); Files.writeString(f, "z"); Files.setLastModifiedTime(f, FileTime.fromMillis(INSIDE)) }
        assertEquals(1, b.scan(T0, T1).size)
    }

    @Test fun withoutAPhotoOrWithAnIncompleteOneNothingIsReported() {
        val b = base()
        put("new.txt", "x", INSIDE)
        assertTrue(b.scan(T0, T1).isEmpty(), "pas de photo : tout passerait pour une création")
        put("a.txt", "a"); put("b.txt", "b"); put("c.txt", "c")
        val small = base(maxEntries = 2); small.capture()
        put("d.txt", "d", INSIDE)
        assertTrue(small.scan(T0, T1).isEmpty(), "projet plus gros que la limite : photo incomplète")
    }

    @Test fun recaptureOnlyRereadsChangedFilesAndStartsFromTheNewState() {
        val f = put("A.kt", "v1")
        val b = base(); b.capture()
        Files.writeString(f, "v2 plus long"); Files.setLastModifiedTime(f, FileTime.fromMillis(OLD + 1000))
        b.capture()                                           // nouvelle photo = nouvel « avant »
        Files.writeString(f, "v3"); Files.setLastModifiedTime(f, FileTime.fromMillis(INSIDE))
        assertEquals("v2 plus long", b.scan(T0, T1).single().before)
    }
}

class ChangeTrackerCommandTest {
    @TempDir lateinit var tmp: Path
    private val T0 = 1_800_000_000_000L
    private val T1 = T0 + 5_000
    private val ideStats: (String, String) -> Pair<Int, Int> = { a, b -> LineDiff.stats(a, b, com.intellij.diff.comparison.ComparisonManagerImpl()) }

    private fun put(rel: String, text: String, mtime: Long): Path {
        val f = tmp.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text)
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime)); return f
    }

    private fun tracker() = ChangeTracker({ tmp.toString() }, stats = ideStats)
    private fun baseline() = FsBaseline({ tmp.toString() })

    @Test fun commandChangesAreListedLikeEditToolChanges() {
        val f = put("A.kt", "un\ndeux\n", T0 - 99_999); put("gone.kt", "x\ny\n", T0 - 99_999)
        val b = baseline(); b.capture(); val t = tracker()
        Files.writeString(f, "un\nDEUX\n"); Files.setLastModifiedTime(f, FileTime.fromMillis(T0 + 1000))
        put("nouveau.kt", "a\nb\nc\n", T0 + 1000); Files.delete(tmp.resolve("gone.kt"))
        t.afterCommand(b, T0, T1)
        val by = t.changes().associateBy { it.rel }
        assertEquals(ChangeStatus.MODIFIED, by.getValue("A.kt").status); assertEquals("un\ndeux\n", by.getValue("A.kt").before)
        assertEquals(ChangeStatus.NEW, by.getValue("nouveau.kt").status)
        assertEquals(ChangeStatus.DELETED, by.getValue("gone.kt").status)
    }

    @Test fun userEditsOutsideTheCommandNeverAppear() {
        val f = put("A.kt", "x", T0 - 99_999); put("B.kt", "y", T0 - 99_999)
        val b = baseline(); b.capture(); val t = tracker()
        Files.writeString(f, "modifié à la main"); Files.setLastModifiedTime(f, FileTime.fromMillis(T1 + 120_000))
        t.afterCommand(b, T0, T1)
        assertTrue(t.changes().isEmpty())
    }

    @Test fun aFileAlreadyTrackedKeepsItsOriginalState() {
        val f = put("A.kt", "v0", T0 - 99_999)
        val b = baseline(); b.capture(); val t = tracker()
        Files.writeString(f, "v1"); Files.setLastModifiedTime(f, FileTime.fromMillis(T0 + 1000)); t.afterCommand(b, T0, T1)
        b.capture()                                            // réponse suivante : nouvelle photo (v1)
        Files.writeString(f, "v2"); Files.setLastModifiedTime(f, FileTime.fromMillis(T1 + 10_000)); t.afterCommand(b, T1 + 9_000, T1 + 11_000)
        assertEquals("v0", t.changes().single().before)
    }

    @Test fun fileEditedByTheUserBetweenTurnsIsForgottenSoItsDiffIsClaudesOnly() {
        val f = put("A.kt", "v0", T0 - 99_999)
        val b = baseline(); b.capture(); val t = tracker()
        Files.writeString(f, "v1 par Claude"); Files.setLastModifiedTime(f, FileTime.fromMillis(T0 + 1000)); t.afterCommand(b, T0, T1)
        t.endTurn()
        // l'utilisateur modifie le fichier entre deux réponses
        Files.writeString(f, "v1 par Claude + modif utilisateur")
        t.startTurn()
        assertTrue(t.changes().isEmpty(), "ce fichier repart de zéro : sa modification d'avant n'est plus attribuée à Claude")
    }

    @Test fun untouchedFilesStayTrackedAcrossTurns() {
        val f = put("A.kt", "v0", T0 - 99_999)
        val b = baseline(); b.capture(); val t = tracker()
        Files.writeString(f, "v1"); Files.setLastModifiedTime(f, FileTime.fromMillis(T0 + 1000)); t.afterCommand(b, T0, T1)
        t.endTurn(); t.startTurn()
        assertEquals("v0", t.changes().single().before)
    }
}
