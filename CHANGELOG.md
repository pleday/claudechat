# Changelog

## Non publié

- **Mode de permission appliqué tout de suite** : changer le mode (Demander, Modifs auto, Plan…) pendant que Claude travaille prend effet immédiatement, sans attendre le message suivant (sauf « Tout autoriser », que la CLI n'autorise pas en cours de session)
- **Votre message épinglé en haut** : une ligne discrète reste visible au-dessus de la conversation, un clic l'affiche en entier. Si votre dernier message ne fait que demander de reprendre (« continue », « reprends là où tu t'étais arrêté », « recommence »…), c'est le message précédent, celui qui contient la tâche, qui est épinglé (repéré par ↻)
- **Détail du contexte** : un clic sur l'anneau de contexte affiche la répartition mesurée par la CLI (prompt système, outils, skills, messages…, détail des messages, seuil de compaction automatique). Mesure exacte via le processus de la conversation, sinon estimation hors serveurs MCP
- **Modèles et niveaux d'effort lus depuis la CLI** : le menu du modèle propose ceux que la CLI annonce (nouveaux modèles, noms, descriptions en info-bulle, versions précédentes regroupées à part), et seuls les efforts acceptés par le modèle choisi sont proposés ; la liste figée ne sert plus que de secours
- **Confirmation avant d'interrompre Claude** : fermer l'onglet, démarrer une nouvelle conversation ou charger une autre session pendant qu'il répond demande confirmation (et prévient des messages en attente qui seraient perdus)
- **Icône « recommencer »** pour la nouvelle conversation de l'onglet, avec une info-bulle explicite : le « + » reste réservé au nouvel onglet
- **Durée de la réponse** : la barre de statut affiche « réponse en 1 min 23 s » une fois la réponse terminée (elle ne reste plus figée sur « Réponse en cours »)
- **Historique de saisie** : ↑ sur la première ligne du champ rappelle les messages envoyés, ↓ sur la dernière redescend jusqu'au brouillon
- **Liens vers les images** : les chemins d'images cités dans les réponses (hors blocs de code) s'ouvrent dans l'éditeur s'ils existent
- **Rendu plus fluide** : le HTML de chaque message est mis en cache, seule la réponse en cours est recalculée ; l'existence des fichiers cités est mise en cache, le rafraîchissement des fichiers modifiés par Claude est regroupé (et ciblé sur le fichier édité), la recherche `@` est débouncée et hors du thread de l'IDE
- **Liste des sessions plus rapide** : titres mis en cache, une seule lecture de date par fichier ; `session-meta.json` est écrit de façon atomique et lu sans planter s'il est incomplet
- **Accès à distance** : le code d'appairage expire après 5 minutes et est révoqué après 5 essais erronés ; une mention `@` reçue du téléphone ne peut plus joindre un fichier hors du projet
- Un événement illisible de la CLI est ignoré (et journalisé) au lieu d'interrompre toute la réponse
- Intégration continue GitHub et `make compat` (vérification de compatibilité avec les IDE récents)

## 0.8.4

- **Pièce jointe seule** : une image ou un fichier joint suffit pour envoyer un message (le bouton d'envoi s'active sans texte)
- **URLs cliquables** : les adresses écrites dans les réponses de Claude s'ouvrent dans le navigateur
- **Plus d'ascenseur horizontal** parasite : les blocs de code passent à la ligne au lieu d'élargir tout le panneau
- **Copier au survol** : un bouton apparaît sur les blocs de code et les citations pour copier leur contenu seul ; le bouton « Copier la dernière réponse » de la barre d'outils est supprimé
- Sablier animé pendant que Claude répond

## 0.8.3

- **Collage d'images** : Ctrl+V / Maj+Inser et le glisser-déposer passent maintenant par un `TransferHandler` et un raccourci dédiés (le collage ne passait pas par `paste()` dans l'IDE) ; les fichiers copiés depuis un gestionnaire de fichiers sont aussi joints. Du texte copié avec une image (tableur, page web) reste collé comme texte
- Ctrl+V / Maj+Inser sont interceptés au niveau de la file d'événements de l'IDE quand le champ de saisie a le focus : l'IDE les traitait avant le composant, d'où le collage d'image qui ne faisait rien
- Collage d'image sous Wayland : si `imageFlavor` n'est pas fourni, l'image est lue depuis les formats `image/png`, `image/jpeg`… du presse-papiers ; les formats disponibles sont journalisés (« Collage (…) : formats du presse-papiers ») pour le diagnostic
- **Aperçu des pièces jointes** : miniatures des images (clic pour agrandir), pastilles pour les fichiers, retrait pièce par pièce (×) au lieu de la simple liste de noms

- Suppression de la case « Autoriser la télémétrie » des réglages : elle n'était lue par aucun code et ne déclenchait aucun envoi

## 0.8.2

- **Serveurs MCP prêts avant l'envoi** : la CLI démarre ses serveurs MCP en tâche de fond, et au premier message ceux encore « pending » n'avaient pas d'outils (Claude les croyait déconnectés, par exemple SonarQube). Le plugin attend maintenant leur connexion (15 s max, requête `mcp_status`) avant d'envoyer, et affiche une notice si un serveur est en échec ou toujours en attente
- Les processus utilitaires (liste des commandes `/`, mesure du quota) ne démarrent plus les serveurs MCP de l'utilisateur

## 0.8.1

- **Permissions « Stream closed » (suite)** : la CLI peut reprendre du travail après un `result` (tâche de fond, message empilé). Le plugin garde alors l'entrée ouverte (fermeture seulement après 3 min de silence), repasse en « en cours » et affiche normalement les demandes de permission, au lieu de les perdre. Le processus d'un tour terminé est arrêté dès qu'un nouveau message est envoyé

## 0.8.0

- Icône du plugin (clair et sombre) pour le Marketplace et la fenêtre des plugins

- **Permissions « Stream closed »** : l'entrée de la CLI n'est plus fermée dès la fin d'un tour mais quand elle est silencieuse (1,5 s, plafond 2 min), pour ne plus faire échouer les permissions d'un travail qui continue. Une permission demandée après la fin du tour est refusée avec un message clair (et une notice dans le panneau) ; les événements tardifs sont journalisés dans `idea.log`

- **Auto-complétion de `/`** : popup flottant au niveau du curseur, navigation ↑/↓, Entrée ou Tab pour valider, Échap pour fermer. Les commandes viennent de la CLI (requête `initialize`, sans appel au modèle), regroupées par catégorie, avec descriptions en français pour les commandes connues ; les raccourcis du plugin passent en premier
- Même popup pour les mentions `@fichier` : liste préchargée à l'ouverture, dossiers cachés et fichiers ignorés exclus, fichiers ouverts puis récents proposés pour un `@` seul

## 0.7.0

- **Ouverture du panneau plus rapide** : les actions de la barre d'outils et des menus se rafraîchissent hors du thread d'interface (`ActionUpdateThread.BGT`) (l'IDE signalait jusqu'à plusieurs secondes d'attente de ce thread au démarrage)
- Dépôt de mises à jour du plugin (`updatePlugins.xml`) pour que l'IDE identifie l'origine du plugin ; `make repo` le régénère
- Construction du plugin indépendante d'un second IDE (index de recherche des réglages désactivé), même quand `make run` est ouvert
- **Serveurs MCP** : nouvelle fenêtre (liste groupée par portée Utilisateur / Projet / Local / claude.ai, badges de statut « Connecté », « Authentification requise », « Échec », détails dépliables avec secrets masqués, recherche, actualisation) et fenêtre « Ajouter un serveur MCP » (nom facultatif, portée, configuration JSON `mcpServers` ou d'un seul serveur). S'appuie sur `claude mcp list / add-json / remove` ; remplace l'ancien tableau des réglages

## 0.6.0

- Écran d'accueil à chaque nouvel onglet : portrait (ressource `images/avatar.png`, remplaçable) et astuces qui tournent
- Transcript plus lisible : « Vous » et « Claude » en gras et en couleur, barre d'accent, modèle affiché à côté de Claude
- Pièces jointes **vidéo** (mp4, mov, webm, mkv, avi…) : images extraites avec ffmpeg et envoyées à Claude avec les instants (la piste audio n'est pas transmise)
- Zone de saisie refondue : carte à bordure d'accent, « + », permissions, fichier courant (cliquable), modèle/effort, anneau de contexte, bouton d'envoi rond avec menu « Mettre en file d'attente / Préciser l'exécution en cours »
- **Préciser** une réponse en cours : le message est injecté au prochain point d'arrêt de Claude, sans l'interrompre (Ctrl+Entrée ou menu du bouton)
- File d'attente visible : le dernier message empilé s'affiche, un clic ouvre le détail de tous (ordre, suppression, envoi immédiat en précision)

## 0.5.0 — première release

Réécriture complète : le plugin pilote désormais la CLI `claude` (plus de clé API).

- Conversation en streaming (`claude -p`, stream-json), réflexion et cartes d'outils, interruption (Échap)
- Approbation des actions (autoriser / refuser / tout autoriser), plans et questions de Claude dans le panneau
- Diff IntelliJ modifiable avant d'accepter une modification ; rechargement des fichiers modifiés
- Sessions de la CLI : liste paginée, reprise, renommage, archivage, suppression ; onglets multiples fermables ; fork
- Contexte de l'éditeur (fichier / sélection), mentions `@fichier`, images (fichier ou presse-papiers), recherche dans la conversation
- Modèles avec numéro de version, effort, mode de permission ; message de commit généré
- Quotas : batterie 5 h (vert ≥ 50 %, orange ≥ 20 %, rouge) avec détail 5 h / 7 j, mesure automatique, bouton Actualiser
- Reprise automatique quand le quota est rempli (délai réglable, 2 min par défaut)
- Déblocage à distance via Telegram (appairage, boutons, réponse libre, échange bidirectionnel), par conversation
- Serveurs MCP supplémentaires, mise à jour de la CLI depuis les réglages

Prérequis : IntelliJ IDEA ≥ 2024.3, JDK 21, CLI `claude` connectée.
