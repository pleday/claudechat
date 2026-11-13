# Claude Chat — plugin JetBrains pour Claude Code

Interface graphique pour la CLI `claude` dans un IDE JetBrains (≥ 2024.3) : conversation en streaming,
cartes d'outils, approbation des actions, diff IntelliJ modifiable, sessions de la CLI reprenables, MCP.

## Fonctionnalités
- **Conversation** en streaming, avec cartes d'outils, réflexion, recherche, historique de saisie (↑/↓), pièces jointes (collage d'images, fichiers, vidéos), mentions `@fichier` et commandes `/` de la CLI.
- **Contrôle** : approbation des actions, plan et questions de Claude (dans l'IDE ou sur le téléphone), diff IntelliJ modifiable avant d'accepter, modes de permission changeables en cours de réponse, messages empilés ou précisions injectées sans interrompre.
- **Fichiers modifiés par Claude** : la liste des fichiers que Claude a créés, modifiés ou supprimés dans la conversation, par ses outils d'édition comme par des commandes (Bash, sous-agents, outils MCP), s'ouvre dans l'arbre des changements et l'outil de comparaison de l'IDE. Indépendant de git ; vos propres modifications n'y figurent pas.
- **Suivi** : quotas (batterie de la session de 5 h), détail du contexte au clic sur l'anneau, durée de la réponse, coût, titre de session généré par la CLI, reprise automatique après un quota atteint.
- **Confort** : message de l'utilisateur épinglé en haut (repli sur la tâche précédente quand on ne fait que dire « continue »), suggestion du message suivant, modèles et efforts lus depuis la CLI, sessions de la CLI reprenables, serveurs MCP.

## Prérequis
- JDK 21
- CLI `claude` installée et connectée (`claude auth login`)

## Commandes
| Commande | Rôle |
|----------|------|
| `make build` | compile |
| `make test` | tests unitaires (JUnit 5, faux exécutable `claude` pour le protocole) |
| `make plugin` | tests puis zip installable dans `build/distributions/` |
| `make run` | IDE de test avec le plugin |
| `make verify` | contrôle de la configuration du plugin |
| `make compat` | compatibilité binaire avec les IDE récents (`verifyPlugin`, télécharge les IDE) |

Sans `make` : `./gradlew test`, `./gradlew buildPlugin`, `./gradlew runIde`.

## Organisation
- `CliClient.kt` — un processus `claude -p` en stream-json par message ; permissions via le protocole de contrôle.
- `Sessions.kt` / `SessionsDialog.kt` — lecture des sessions de la CLI (`~/.claude/projects`), métadonnées du plugin dans `~/.claude-code-gui` (ou `CCG_HOME`).
- `ChatPanel.kt` — panneau de conversation, approbations, diff, mentions `@`, collage d'images.
- `Remote.kt` — accès à distance : bot Telegram (long polling) + `RemoteHub` (première réponse IDE/téléphone gagnante).
- `TextTools.kt` — fonctions pures (recherche floue, mentions, surlignage, résumé d'outil, catalogue de modèles).
- `CliInfo.kt` — ce que la CLI annonce : modèles et efforts (`initialize`), détail du contexte (`get_context_usage`).
- `ChangeTracker.kt` / `FsBaseline.kt` / `ChangedFilesView.kt` — fichiers modifiés par Claude : état avant chaque modification, photo du projet pour les commandes, fenêtre basée sur les composants de l'IDE.
- `TranscriptRenderer.kt` / `RenderCache.kt` / `Linkify.kt` — rendu HTML de la conversation (mis en cache entrée par entrée) et liens vers les fichiers cités.
- `StickyPrompt.kt`, `InputHistory.kt`, `Images.kt` — message épinglé, historique de saisie, utilitaires d'images.
- `src/test/kotlin` — tests. `RealCliIT` appelle la vraie CLI (`CLAUDE_REAL=1 ./gradlew test --tests '*RealCliIT*'`, consomme un peu de quota). La CI GitHub lance `test` et `buildPlugin`, puis `verifyPlugin`.

Voir la spec fonctionnelle (FR-xx) pour le périmètre ; les exigences non couvertes sont listées dans `docs/ECARTS.md`.

## Limites connues
- **Fichiers modifiés par des commandes** : retrouvés par comparaison avec une photo du projet prise avant la réponse. Ne sont pas parcourus : `.git`, `.idea`, `.gradle`, `node_modules`, `build`, `out`, `target`, `dist` et autres dossiers générés. Les fichiers binaires ou de plus de 256 Ko ne sont pas comparables. Au-delà de 60 000 fichiers, seuls les outils d'édition de Claude sont suivis. Une modification faite à la main pendant l'exécution d'une commande peut lui être attribuée.
- **Suggestion du message suivant** : une petite requête en plus après chaque réponse (environ 10 % de coût), désactivable dans les réglages.
- L'IDE de test (`make run`, IDE 2024.3) désactive son plugin Gradle, qui plante au démarrage sur les versions récentes de Java ; sans conséquence pour le plugin.

## Débloquer une session depuis un smartphone
1. Dans Telegram, crée un bot avec `@BotFather` et copie son jeton.
2. Réglages > Outils > Claude Chat > *Accès à distance* : active, colle le jeton, applique.
3. Clique *Appairer mon téléphone*, puis envoie `/pair <code>` à ton bot.

Ensuite, active l'interrupteur 📱 de la barre d'outils **dans la conversation voulue** (désactivé par défaut, réglable). Pour cette conversation, chaque demande d'autorisation, plan ou question arrive sur le téléphone avec des boutons
(réponse libre possible pour les questions). La première réponse, depuis l'IDE ou le téléphone, l'emporte.
Seul le chat appairé est écouté. Le contenu des demandes transite par Telegram.

## Mises à jour dans l'IDE
Le plugin n'étant pas sur le JetBrains Marketplace, l'IDE ne sait pas où chercher ses mises à jour
(« Cannot update plugin: unable to identify where Claude Chat was installed from »). On lui indique un dépôt personnalisé :

1. *Réglages > Plugins > ⚙ > Manage Plugin Repositories… > +*
2. Ajouter : `https://raw.githubusercontent.com/pleday/claudechat/main/updatePlugins.xml`

Après chaque release : `make repo` régénère `updatePlugins.xml` pour le dernier tag ; le commiter et le pousser.
