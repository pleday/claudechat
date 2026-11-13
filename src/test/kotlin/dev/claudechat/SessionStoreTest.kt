package dev.claudechat

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SessionStoreTest {
    @TempDir lateinit var tmp: Path
    private val cwd = "/home/u/mon-projet.v2"
    private val oldClaude = SessionStore.claudeDir

    @BeforeEach fun setUp() {
        SessionStore.claudeDir = tmp.resolve("claude")
        DataHome.override = tmp.resolve("ccg")
        Files.createDirectories(SessionStore.projectDir(cwd))
    }

    @AfterEach fun tearDown() { SessionStore.claudeDir = oldClaude; DataHome.override = null }

    private fun write(id: String, vararg lines: String, ageSec: Long = 0) {
        val f = SessionStore.projectDir(cwd).resolve("$id.jsonl")
        Files.writeString(f, lines.joinToString("\n"))
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - ageSec * 1000))
    }

    private val user = """{"type":"user","message":{"role":"user","content":"Bonjour Claude"}}"""
    private val asst = """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"Salut"},{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/x"}}]}}"""
    private val res = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"contenu","is_error":false}]}}"""

    @Test fun projectDirEncodesNonAlphanumerics() =
        assertEquals("-home-u-mon-projet-v2", SessionStore.projectDir(cwd).fileName.toString())

    @Test fun listSortsByRecencyAndPaginates() {
        write("old", user, ageSec = 100); write("new", user, ageSec = 1); write("mid", user, ageSec = 50)
        assertEquals(listOf("new", "mid", "old"), SessionStore.list(cwd).map { it.id })
        assertEquals(listOf("mid"), SessionStore.list(cwd, offset = 1, limit = 1).map { it.id })
    }

    @Test fun titlePrefersAiTitleThenFirstUserMessage() {
        write("a", user, """{"type":"ai-title","aiTitle":"Titre IA"}""")
        write("b", user, ageSec = 10)
        val byId = SessionStore.list(cwd).associateBy { it.id }
        assertEquals("Titre IA", byId["a"]!!.title)
        assertEquals("Bonjour Claude", byId["b"]!!.title)
    }

    @Test fun renameAndArchive() {
        write("a", user)
        SessionStore.rename("a", "Perso")
        assertEquals("Perso", SessionStore.list(cwd).single().title)
        SessionStore.setArchived("a", true)
        assertTrue(SessionStore.list(cwd).isEmpty())
        assertTrue(SessionStore.list(cwd, includeArchived = true).single().archived)
    }

    @Test fun deleteRemovesFile() {
        write("a", user)
        val info = SessionStore.list(cwd).single()
        SessionStore.delete(info)
        assertFalse(Files.exists(info.file))
    }

    @Test fun loadRebuildsTranscriptWithToolResults() {
        write("a", user, asst, res)
        val s = SessionStore.load(SessionStore.list(cwd).single())
        assertEquals(listOf(Kind.USER, Kind.THINKING, Kind.TEXT, Kind.TOOL), s.entries.map { it.kind })
        val tool = s.entries.last()
        assertEquals("Read", tool.toolName)
        assertEquals("contenu", tool.result)
        assertEquals("a", s.id)
    }

    @Test fun loadSkipsMetaAndCommandMessages() {
        write("a", """{"type":"user","isMeta":true,"message":{"content":"meta"}}""",
            """{"type":"user","message":{"content":"<command-name>/clear</command-name>"}}""", user)
        assertEquals(1, SessionStore.load(SessionStore.list(cwd).single()).entries.size)
    }

    @Test fun titleIsRecomputedOnlyWhenTheFileChanges() {
        write("a", user, ageSec = 100)
        assertEquals("Bonjour Claude", SessionStore.list(cwd).single().title)
        // même date de modification : le titre vient du cache même si le contenu a changé
        val f = SessionStore.projectDir(cwd).resolve("a.jsonl")
        val mtime = Files.getLastModifiedTime(f)
        Files.writeString(f, """{"type":"user","message":{"role":"user","content":"Autre"}}""")
        Files.setLastModifiedTime(f, mtime)
        assertEquals("Bonjour Claude", SessionStore.list(cwd).single().title)
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(mtime.toMillis() + 5000))
        assertEquals("Autre", SessionStore.list(cwd).single().title)
    }

    @Test fun incompleteMetaFileIsTolerated() {
        write("a", user)
        Files.writeString(DataHome.dir.resolve("session-meta.json"), """{"titles":{"a":"Mon titre"}}""")
        assertEquals("Mon titre", SessionStore.list(cwd).single().title)
        SessionStore.setArchived("a", true)
        assertTrue(SessionStore.list(cwd).isEmpty())
        assertEquals("Mon titre", SessionStore.list(cwd, includeArchived = true).single().title)
    }

    @Test fun corruptMetaFileIsIgnoredAndMetaSaveLeavesNoTempFile() {
        write("a", user)
        Files.writeString(DataHome.dir.resolve("session-meta.json"), "{pas du json")
        assertEquals("Bonjour Claude", SessionStore.list(cwd).single().title)
        SessionStore.rename("a", "Neuf")
        assertEquals("Neuf", SessionStore.list(cwd).single().title)
        assertFalse(Files.exists(DataHome.dir.resolve("session-meta.json.tmp")))
    }
}
