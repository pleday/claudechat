# Claude Chat — extension VS Code pour Claude Code

Interface graphique pour la CLI `claude` dans VS Code (≥ 1.90) : conversation en streaming, cartes d'outils, approbation des
actions, diff modifiable, sessions de la CLI reprenables, MCP. C'est le pendant du plugin JetBrains
(`../claude-chat-jetbrains`), avec les mêmes fonctionnalités.

## Fonctionnalités
- **Conversation** en streaming (Markdown, réflexion, cartes d'outils), historique de saisie (↑/↓), pièces jointes (collage d'images, fichiers, vidéos via ffmpeg), mentions `@fichier` et commandes `/` de la CLI.
- **Contrôle** : approbation des actions, plan et questions de Claude (dans VS Code ou sur le téléphone), **diff modifiable** avant d'accepter (la partie droite est éditable : ce que vous y laissez est appliqué), modes de permission changeables en cours de réponse, messages empilés ou précisions injectées sans interrompre.
- **Fichiers modifiés par Claude** : la liste des fichiers créés, modifiés ou supprimés dans la conversation (par ses outils d'édition comme par des commandes Bash, sous-agents, outils MCP) s'affiche dans une vue dédiée ; un clic ouvre la comparaison « avant Claude ↔ actuel ». Indépendant de git ; vos propres modifications n'y figurent pas.
- **Suivi** : quotas (batterie de la session de 5 h), détail du contexte au clic sur l'anneau, durée de la réponse, coût, titre de session généré par la CLI, reprise automatique après un quota atteint.
- **Confort** : message épinglé en haut (repli sur la tâche précédente quand on ne fait que dire « continue »), suggestion du message suivant, modèles et efforts lus depuis la CLI, sessions reprenables (renommer, archiver, supprimer, ouvrir dans un onglet), serveurs MCP, plusieurs conversations (vue latérale + onglets), recherche dans la conversation.
- **Éditeur** : clic droit sur une sélection > *Claude Chat* (Expliquer, Corriger, Tests, Documenter, Revue, Envoyer la sélection — `Ctrl+Alt+Maj+C`) ; bouton ✨ du contrôle de code source pour **générer le message de commit**.
- **Accès à distance** : bot Telegram pour répondre depuis un smartphone (voir ci-dessous).

## Prérequis
- CLI `claude` installée et connectée (`claude auth login`).
- Node.js 20+ pour construire l'extension.

## Commandes
| Commande | Rôle |
|----------|------|
| `make build` | bundles `dist/extension.js` et `dist/webview.js` |
| `make test` | tests unitaires (vitest : protocole avec un faux `claude`, sessions, suivi des fichiers, MCP, Telegram, interface dans jsdom) |
| `make e2e` | test d'intégration dans un vrai VS Code (téléchargé dans `.vscode-test`, ouvre une fenêtre un instant) |
| `make package` | tests puis `claude-chat-vscode-X.Y.Z.vsix` installable |
| `make run` | VS Code de développement avec l'extension (équivalent de F5) |

Sans `make` : `npm ci`, `npm run typecheck`, `npm test`, `npm run build`, `npm run package`.
Installer le `.vsix` : *Extensions > … > Install from VSIX…* ou `code --install-extension claude-chat-vscode-X.Y.Z.vsix`.

## Organisation
- `src/core/` — logique **sans dépendance à VS Code** (testable seule) : `cli.ts` (un processus `claude -p` en stream-json par message, permissions par le protocole de contrôle), `sessions.ts` (sessions de la CLI dans `~/.claude/projects`, métadonnées dans `~/.claude-code-gui`, **partagées avec le plugin JetBrains**), `changes.ts` (fichiers modifiés par Claude), `mcp.ts`, `remote.ts` (Telegram), `textTools.ts`, `models.ts`, `cliInfo.ts`…
- `src/host/` — l'extension : `chatSession.ts` (une conversation : état, approbations, file d'attente, quotas ; ne dépend que de l'interface `SessionEnv`), `chatHost.ts` (branche une conversation sur VS Code), `extension.ts` (commandes, vues), `virtualDocs.ts` (diff modifiable), `changesView.ts`, `pickers.ts` (sessions, MCP), `commitMessage.ts`.
- `src/webview/` — l'interface (TypeScript → `dist/webview.js`, styles dans `media/webview.css`) ; `src/protocol.ts` décrit les messages échangés avec l'hôte.
- `test/` — tests unitaires ; `e2e/` — test d'intégration.
- Les icônes, l'avatar, les astuces et le catalogue des commandes viennent de `../shared` (copiés dans `media/` au build, jamais committés).

## Réglages
Tous sous `claudeChat.*` (Paramètres > Extensions > Claude Chat) : exécutable `claude`, modèle, effort, mode de permission, prompt système, notifications, reprise automatique, mode d'envoi pendant une réponse (préciser / file d'attente), diff automatique, suggestions, accès à distance.

## Limites connues
- **Fichiers modifiés par des commandes** : retrouvés par comparaison avec une photo du projet prise avant la réponse. Ne sont pas parcourus : `.git`, `.idea`, `.gradle`, `node_modules`, `build`, `out`, `target`, `dist` et autres dossiers générés. Les fichiers binaires ou de plus de 256 Ko ne sont pas comparables. Au-delà de 60 000 fichiers, seuls les outils d'édition de Claude sont suivis.
- **Suggestion du message suivant** : une petite requête en plus après chaque réponse (environ 10 % de coût), désactivable dans les réglages.
- Dossier du projet : le premier dossier de l'espace de travail (pas de multi-racines).
- Fermer un onglet de conversation pendant une réponse l'interrompt sans confirmation (VS Code ne permet pas de s'y opposer).
- Le glisser-déposer de fichiers dans la zone de saisie n'est pas pris en charge : utiliser le bouton **+**, ou les mentions `@`.
- Écarts avec la spécification fonctionnelle : voir `docs/ECARTS.md` ; liste de validation manuelle : `docs/VALIDATION.md`.

## Débloquer une session depuis un smartphone
1. Dans Telegram, crée un bot avec `@BotFather` et copie son jeton.
2. Commande **Claude Chat : Appairer mon téléphone** : colle le jeton (stocké dans le coffre sécurisé de VS Code), puis envoie `/pair <code>` à ton bot.
3. Active l'interrupteur 📱 de la barre d'outils **dans la conversation voulue**.

Pour cette conversation, chaque demande d'autorisation, plan ou question arrive sur le téléphone avec des boutons
(réponse libre possible pour les questions). La première réponse, depuis VS Code ou le téléphone, l'emporte.
Seul le chat appairé est écouté ; le code d'appairage expire après 5 minutes. Le contenu des demandes transite par Telegram.
