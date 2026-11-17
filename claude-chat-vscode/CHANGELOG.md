# Changelog

## 0.9.6

- **`/mcp reconnect|enable|disable <serveur>`** : fonctionne enfin (la CLI refusait ces actions comme commande hors terminal). Le plugin utilise les requêtes de contrôle équivalentes : activer ou désactiver un serveur est mémorisé par la CLI pour le projet et pris en compte dès le prochain message ; une erreur (serveur inconnu…) s'affiche dans la conversation

## 0.9.5

- **Commandes `/` de la CLI** : la réponse des commandes locales (`/context`, `/cost`, `/usage`, `/help`…) s'affiche enfin dans la conversation ; elle était perdue, d'où l'impression que « rien ne se passe »
- **Commandes gérées par le plugin** : `/mcp` ouvre l'écran des serveurs MCP, `/clear` démarre une nouvelle conversation, `/model <nom>` et `/effort <niveau>` règlent les sélecteurs du plugin (la CLI ne les appliquait qu'à un processus relancé à chaque message)
- **Suggestions d'arguments** : après `/model `, `/effort `, `/mcp ` (actions, puis serveurs configurés) et `/advisor `, la complétion propose les valeurs possibles
- **Complétion `/`** : une commande tapée en entier n'est plus reproposée, Entrée l'envoie au lieu de la compléter

## 0.9.4

- **Zone de saisie** : plus haute par défaut (3 lignes) et redimensionnable : poignée au bord haut de la carte (glisser pour agrandir ou réduire, double-clic pour revenir à la taille par défaut), hauteur mémorisée
- **Interface harmonisée** : barre d'outils en icônes SVG regroupées (conversation, affichage, automatismes), puces de contrôle de hauteur uniforme, icônes cohérentes pour l'envoi, l'arrêt, le fichier courant, la batterie et l'attente, carte de saisie plus aérée

## 0.9.3

- **Fichiers modifiés par Claude** : moins de bruit. Les fichiers écrits dans un dossier temporaire (`/tmp`, scratchpad de Claude), dans `.claude/worktrees` ou dans un dossier généré (`build`, `node_modules`…) ne sont plus listés, et les worktrees ne sont plus parcourus par la détection des changements faits par les commandes

## 0.9.2

- **Licence MIT** : l'extension est désormais publiée sous licence MIT (fichier `LICENSE` à la racine du dépôt)

## 0.9.1

- **Suggestion du message suivant** : elle devient le texte par défaut du champ de saisie vide (Tab l'insère) au lieu d'une pastille
- **Pastille « Aller en bas »** : quand on est remonté dans la conversation, elle ramène au dernier message et réactive le suivi automatique
- **Détail des quotas** : une jauge colorée (verte, orange dès 60 %, rouge dès 85 %) sous chaque quota montre le pourcentage utilisé

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
