package dev.claudechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Les actions du panneau ne doivent pas se rafraîchir sur l'EDT (voir PanelActions.kt). */
class PanelActionsTest {
    @Test fun toolbarActionsUpdateOffTheEdt() {
        assertEquals(ActionUpdateThread.BGT, panelAction("a", null) {}.actionUpdateThread)
        assertEquals(ActionUpdateThread.BGT, panelToggle("t", null, { true }) {}.actionUpdateThread)
        assertEquals(ActionUpdateThread.BGT, panelChoice("c", null, { true }) {}.actionUpdateThread)
    }

    @Test fun togglesReflectTheirStateAndForwardChanges() {
        var on = false
        val t = panelToggle("t", null, { on }) { on = it }
        assertEquals("t", t.templatePresentation.text)
        var chosen = 0
        val c = panelChoice("c", "desc", { chosen == 1 }) { chosen = 1 }
        assertEquals("desc", c.templatePresentation.description)
    }
}
