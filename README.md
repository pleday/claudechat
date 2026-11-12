# Claude Chat — plugin JetBrains pour Claude Code

Interface graphique pour la CLI `claude` dans un IDE JetBrains (≥ 2024.3) : conversation en streaming,
cartes d'outils, approbation des actions, diff IntelliJ modifiable, sessions de la CLI reprenables, MCP.

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

Sans `make` : `./gradlew test`, `./gradlew buildPlugin`, `./gradlew runIde`.

## Organisation
- `CliClient.kt` — un processus `claude -p` en stream-json par message ; permissions via le protocole de contrôle.
- `Sessions.kt` / `SessionsDialog.kt` — lecture des sessions de la CLI (`~/.claude/projects`), métadonnées du plugin dans `~/.claude-code-gui` (ou `CCG_HOME`).
- `ChatPanel.kt` — panneau de conversation, approbations, diff, mentions `@`, collage d'images.
- `Remote.kt` — accès à distance : bot Telegram (long polling) + `RemoteHub` (première réponse IDE/téléphone gagnante).
- `TextTools.kt` — fonctions pures (recherche floue, mentions, surlignage, résumé d'outil).
- `src/test/kotlin` — tests.

Voir la spec fonctionnelle (FR-xx) pour le périmètre ; les exigences non couvertes sont listées dans `docs/ECARTS.md`.

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
