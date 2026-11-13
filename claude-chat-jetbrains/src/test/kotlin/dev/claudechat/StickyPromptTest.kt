package dev.claudechat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StickyPromptTest {
    @Test fun plainResumeRequestsAreRecognised() {
        listOf(
            "continue", "Continue !", "reprends", "reprend", "recommence", "Recommence.", "ok continue", "oui, continue stp",
            "reprends là où tu t'étais arrêté", "reprends ta tâche", "vas-y", "go on", "keep going", "try again", "relance",
            "Le quota est réinitialisé : reprends ta tâche là où tu t'étais arrêté.", "continue s'il te plaît", "réessaie",
        ).forEach { assertTrue(StickyPrompt.isResume(it), "« $it » devrait être une reprise") }
    }

    @Test fun messagesWithNewInstructionsAreNotResumeRequests() {
        listOf(
            "continue avec la partie tests", "recommence en kotlin", "reprends mais sans utiliser Gradle", "ajoute un bouton",
            "explique ce code", "go", "ok", "oui", "merci", "", "   ",
            "continue de lire le fichier ChatPanel.kt et corrige le bug",
        ).forEach { assertFalse(StickyPrompt.isResume(it), "« $it » ne devrait pas être une reprise") }
    }

    @Test fun lastMessageIsPinnedWhenItIsARealPrompt() {
        assertEquals(StickyPrompt.Choice("ajoute un bouton"), StickyPrompt.pick(listOf("explique", "ajoute un bouton")))
    }

    @Test fun previousPromptIsPinnedWhenLastIsAResume() {
        val c = StickyPrompt.pick(listOf("refactorise ChatPanel", "continue"))!!
        assertEquals("refactorise ChatPanel", c.prompt); assertEquals("continue", c.resumedWith)
    }

    @Test fun skipsSeveralConsecutiveResumes() {
        val c = StickyPrompt.pick(listOf("tâche A", "tâche B", "continue", "reprends", "vas-y"))!!
        assertEquals("tâche B", c.prompt); assertEquals("vas-y", c.resumedWith)
    }

    @Test fun onlyResumeMessagesKeepsTheLastOne() {
        assertEquals(StickyPrompt.Choice("continue"), StickyPrompt.pick(listOf("reprends", "continue")))
    }

    @Test fun nothingToPinWithoutMessages() = assertNull(StickyPrompt.pick(emptyList()))

    @Test fun accentsAndPunctuationDoNotMatter() {
        assertTrue(StickyPrompt.isResume("REPRENDS... là où tu t’étais arrêté !"))
        assertTrue(StickyPrompt.isResume("Réessaie"))
    }
}
