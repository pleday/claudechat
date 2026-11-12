package dev.claudechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.Icon

/*
 * Actions de la barre d'outils et des menus du panneau.
 *
 * Leur état (activé / coché) ne dépend que de simples champs : on les met donc à jour sur un thread d'arrière-plan
 * (BGT). Sur le thread d'interface (EDT), chaque rafraîchissement de la barre d'outils attendait la disponibilité de
 * l'EDT au démarrage de l'IDE (« ms total to grab EDT … Use ActionUpdateThread.BGT »), ce qui ralentissait l'ouverture
 * du panneau. Les actions qui lisent l'éditeur (sélection, etc.) restent sur l'EDT : voir ContextActions.
 */

/** Action ordinaire : `enabled()` est évalué hors EDT, `run()` s'exécute sur l'EDT comme toute action. */
fun panelAction(text: String, icon: Icon?, enabled: () -> Boolean = { true }, run: () -> Unit): DumbAwareAction =
    object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
        override fun actionPerformed(e: AnActionEvent) = run()
    }

/** Bouton à bascule de la barre d'outils. */
fun panelToggle(text: String, icon: Icon?, get: () -> Boolean, set: (Boolean) -> Unit): ToggleAction =
    object : ToggleAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent) = get()
        override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
    }

/** Entrée à coche d'un menu (permissions, modèle, effort). */
fun panelChoice(text: String, desc: String?, selected: () -> Boolean, set: () -> Unit): ToggleAction =
    object : ToggleAction(text, desc, null) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent) = selected()
        override fun setSelected(e: AnActionEvent, state: Boolean) { if (state) set() }
    }
