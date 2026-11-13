# Claude Chat — Claude Code dans votre éditeur

Interface graphique pour la CLI [`claude`](https://claude.com/claude-code), en deux projets qui offrent les mêmes fonctionnalités :

| Projet | Éditeur | Technologie | Documentation |
|--------|---------|-------------|---------------|
| [`claude-chat-jetbrains`](claude-chat-jetbrains) | IntelliJ IDEA et autres IDE JetBrains ≥ 2024.3 | Kotlin, Gradle, Swing | [README](claude-chat-jetbrains/README.md) |
| [`claude-chat-vscode`](claude-chat-vscode) | VS Code ≥ 1.90 | TypeScript, esbuild, webview | [README](claude-chat-vscode/README.md) |
| [`shared`](shared) | les deux | JSON, SVG, PNG | ci-dessous |

Conversation en streaming, cartes d'outils, approbation des actions, diff modifiable, fichiers modifiés par Claude, sessions de la CLI
reprenables, quotas, MCP, accès à distance par Telegram : voir le README de chaque projet.

## Ce qui est mutualisé

Kotlin (JVM) et TypeScript ne peuvent pas partager de code ; on mutualise donc ce qui est **données** :

- `shared/assets` — icône, icônes du plugin, avatar de l'écran d'accueil ;
- `shared/data` — `constants.json` (modes de permission, efforts, modèles de secours, prompts `/explain`… et du message de commit, dossiers ignorés, seuils), `tips.json`, `slash-commands.json` ;
- `shared/test-vectors` — cas « entrée → sortie » du comportement métier (message épinglé, quotas, mentions, analyse des réponses de la CLI, MCP, transcript…) exécutés par **les deux** suites de tests : un écart de comportement entre les éditeurs fait échouer un test (voir `shared/README.md`) ;
- le **dossier de données** `~/.claude-code-gui` (titres et archives des sessions) et les sessions de la CLI (`~/.claude/projects`) : une conversation commencée dans l'un des deux éditeurs se reprend dans l'autre.

Chaque build copie ces fichiers dans ses propres ressources (Gradle `processResources`, `esbuild.mjs`) : on n'édite jamais les copies.
Les deux projets implémentent le même protocole avec la CLI (stream-json, requêtes de contrôle) ; quand l'un change, l'autre se vérifie
avec la même liste fonctionnelle (`*/docs/VALIDATION.md`).

## Commandes

| Commande | Rôle |
|----------|------|
| `make test` | tests des deux projets |
| `make build` | plugin JetBrains (zip) et extension VS Code (`.vsix`) |
| `make jetbrains-run` / `make vscode-run` | éditeur de test avec le projet |
| `make e2e` | test d'intégration de l'extension dans un vrai VS Code |

Chaque projet garde ses propres commandes (`claude-chat-jetbrains/Makefile`, `claude-chat-vscode/Makefile`).

## Publication

- **Plugin JetBrains** : tags `vX.Y.Z`, release GitHub avec le zip, puis `claude-chat-jetbrains/scripts/update-repo.sh` met à jour `updatePlugins.xml`
  (laissé à la **racine** : c'est l'URL brute interrogée par les IDE des utilisateurs, ne pas la déplacer). Étapes détaillées dans `claude-chat-jetbrains/README.md`.
- **Extension VS Code** : tags `vscode-vX.Y.Z`, release GitHub avec le `.vsix` (`make -C claude-chat-vscode package`).
