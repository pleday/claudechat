# Changelog

## 0.9.0

Première version de l'extension VS Code, au niveau fonctionnel du plugin JetBrains 0.9.0.

- **Conversation** en streaming (Markdown assaini, réflexion, cartes d'outils, liens vers les fichiers cités), plusieurs conversations (vue latérale et onglets), recherche, message épinglé, historique de saisie, suggestion du message suivant
- **Approbations** des actions, plan et questions de Claude ; **diff modifiable** (la partie droite est éditable, la version corrigée est appliquée à la place de la proposition) ; modes de permission appliqués en cours de réponse ; précisions injectées ou messages empilés pendant que Claude travaille
- **Fichiers modifiés par Claude** (outils d'édition et commandes) dans une vue dédiée et un éditeur de comparaison multiple, indépendamment de git
- **Sessions de la CLI** reprenables, renommables, archivables, supprimables (métadonnées partagées avec le plugin JetBrains) ; titres générés par la CLI
- **Pièces jointes** : images (collage), fichiers, vidéos (images extraites par ffmpeg), mentions `@fichier`, fichier courant ou sélection de l'éditeur
- **Quotas** (batterie de 5 h, détail sur 7 jours), détail du contexte, coût, reprise automatique après un quota atteint
- **Serveurs MCP** : liste avec statut, ajout (JSON), suppression
- **Éditeur** : menu contextuel *Claude Chat*, raccourci `Ctrl+Alt+Maj+C`, génération du message de commit depuis le contrôle de code source
- **Accès à distance** par bot Telegram (jeton dans le coffre sécurisé de VS Code, appairage à code expirant)
