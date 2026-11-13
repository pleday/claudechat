# Validation manuelle avant une version

Les tests automatiques (`make test`, `make e2e`) couvrent la logique, le protocole avec la CLI, le DOM de l'interface (jsdom)
et le chargement dans un vrai VS Code. Cette liste couvre ce qu'ils ne voient pas : l'apparence et les interactions réelles.
Se passe dans le VS Code de développement (`make run`) puis, pour la section G, dans le VS Code habituel avec le `.vsix`.

**Préparation** : un dossier de test avec git (`git init`, deux fichiers, un commit). Faire la passe en thème clair **et** sombre
(et un thème à fort contraste). Garder ouvert *Aide > Basculer les outils de développement* (console du webview : *Developer: Open Webview Developer Tools*).

## A. Démarrage
- [ ] L'icône Claude Chat apparaît dans la barre d'activité ; la vue « Conversation » s'ouvre avec l'accueil (avatar, astuces) ; aucune erreur dans la console du webview.
- [ ] *Claude Chat : Nouvelle conversation dans un onglet* : un second onglet fonctionne indépendamment.
- [ ] Fermer et rouvrir la vue latérale : la conversation en cours est conservée.

## B. Zone de saisie
- [ ] Entrée envoie, Maj+Entrée saute une ligne ; ↑ rappelle les messages envoyés, ↓ redescend jusqu'au brouillon.
- [ ] `@` propose des fichiers, `/` propose les commandes groupées ; Tab / Entrée valident, Échap ferme.
- [ ] Coller une image (Ctrl+V) : pastille avec miniature ; la croix la retire. Bouton **+** > joindre un fichier, une image, une vidéo (si ffmpeg est installé).
- [ ] Les pastilles du haut tiennent sur une rangée alignée à gauche et passent à la ligne si la vue est étroite.

## C. Conversation
- [ ] Message épinglé en haut : un clic le déplie, un second le replie, un nouveau message le replie.
- [ ] Envoyer « continue » après une vraie consigne : le message épinglé reste la consigne, marqué « ↻ ».
- [ ] Fin de réponse : la barre de statut affiche « … · réponse en X s ».
- [ ] Le titre de l'onglet (ou la description de la vue) passe du début du message à un titre généré, quelques secondes après la réponse.
- [ ] Markdown : titres, listes, tableaux, blocs de code (bouton *Copier* au survol), liens `http` (s'ouvrent dans le navigateur), noms de fichiers entre accents graves (s'ouvrent dans l'éditeur).
- [ ] Ctrl+F : recherche dans la conversation, surlignage, suivant/précédent.

## D. Contrôles
- [ ] Changer le mode de permission **pendant** une réponse : l'effet est immédiat (sauf « Tout autoriser » : avis dans la conversation).
- [ ] Nouvelle conversation, rechargement d'une session **pendant** une réponse : confirmation modale ; « Annuler » ne coupe rien.
- [ ] Menu du modèle : alias d'abord, « Autres versions » ensuite ; sur un modèle sans `xhigh`, ce niveau n'est pas proposé.
- [ ] Clic sur l'anneau de contexte : répartition ; pendant une réponse (exacte), puis plus de 3 min après (mention « estimation »).
- [ ] Clic sur la batterie : détail 5 h / 7 jours, bouton *Actualiser*.

## E. Fichiers modifiés par Claude
- [ ] Demander une modification de fichier existant et la création d'un fichier : la pastille « N fichiers modifiés +A −R » apparaît, le compteur s'affiche sur la vue « Fichiers modifiés par Claude ».
- [ ] Clic sur la pastille : l'éditeur de comparaison multiple s'ouvre (avant Claude ↔ actuel) ; un clic sur un fichier de la vue ouvre sa comparaison.
- [ ] Un fichier créé s'ouvre avec une partie gauche vide ; un fichier supprimé avec une partie droite vide.
- [ ] Demander une commande Bash qui crée, modifie, supprime et renomme (`mv`) des fichiers : tous apparaissent.
- [ ] Modifier un fichier **à la main** : il n'apparaît pas ; modifier à la main un fichier déjà modifié par Claude entre deux réponses : il repart de zéro.
- [ ] Commit automatique ou fichier non ajouté à git : la liste est inchangée.
- [ ] La croix de la pastille (ou le bouton de la vue) vide la liste.

## F. Approbations et diff
- [ ] Demande d'autorisation : boutons *Autoriser / Refuser / Tout autoriser*, *Voir le diff* ; le diff s'ouvre tout seul (réglage `autoDiff`).
- [ ] Dans le diff, modifier la partie droite puis *Autoriser* : le fichier contient **votre** version, Claude en est informé ; aucun message « enregistrer ? » à la fermeture.
- [ ] Plan (`ExitPlanMode`) et questions (`AskUserQuestion`, réponse libre) s'affichent et aboutissent.
- [ ] Message pendant une réponse : *Préciser* (⚡) et *File d'attente* (menu ⌄) ; Ctrl+Entrée inverse le mode ; la file se réordonne et se vide.

## G. Autres fonctions
- [ ] *Reprendre une session…* : liste, renommer, archiver / afficher les archivées, supprimer, ouvrir dans un onglet ; le renommage d'un onglet apparaît aussi dans `claude --resume`.
- [ ] *Serveurs MCP…* : liste avec statut, ajout d'un serveur JSON, suppression.
- [ ] Clic droit sur du code sélectionné > *Claude Chat* > Expliquer / Corriger / Tests / Documenter / Revue / Envoyer la sélection ; `Ctrl+Alt+Maj+C`.
- [ ] Contrôle de code source : bouton ✨ « Générer le message de commit » remplit la zone de message.
- [ ] Si configuré : accès à distance Telegram (*Appairer mon téléphone*, demande d'approbation reçue et acceptée depuis le téléphone).

## H. VS Code habituel
- [ ] Installer le `.vsix` (*Extensions > … > Install from VSIX…*), recharger la fenêtre, refaire A, E et F.
