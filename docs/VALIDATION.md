# Validation manuelle avant une version

Les tests automatiques ne couvrent pas l'interface (Swing, fenêtres de l'IDE). Cette liste se passe dans l'IDE de test
(`make run`) puis, pour la section G, dans l'IDE habituel. Cocher chaque ligne ; noter ce qui cloche (capture si possible).

**Préparation** : un projet de test avec git (`git init`, deux fichiers `.kt`/`.md`, un commit). Faire la passe en thème
clair **et** en thème sombre. Garder `build/idea-sandbox/IC-2024.3/log/idea.log` sous la main (voir « Contrôle du log »).

## A. Démarrage
- [ ] Le panneau Claude Chat s'ouvre, la barre d'outils est complète, aucune erreur rouge dans l'IDE.
- [ ] Fermer et rouvrir la fenêtre d'outils ; ouvrir un 2e onglet (« + » du titre) : les deux fonctionnent.

## B. Zone de saisie
- [ ] Entrée envoie, Maj+Entrée saute une ligne ; ↑ rappelle les messages envoyés, ↓ redescend jusqu'au brouillon.
- [ ] `@` propose des fichiers, `/` propose les commandes ; Tab / Entrée valident.
- [ ] Coller une image (Ctrl+V) : pastille avec miniature ; la croix la retire.
- [ ] Rangée de pastilles du haut : alignée à gauche, une seule ligne, retour à la ligne si le panneau est étroit.

## C. Conversation
- [ ] Message épinglé en haut : une ligne bleue, un clic le déplie, un second le replie, un nouveau message le replie.
- [ ] Envoyer « continue » après une vraie consigne : le message épinglé reste la consigne, marqué « ↻ ».
- [ ] Fin de réponse : la barre de statut affiche « … · réponse en X s » et plus « Réponse en cours ».
- [ ] Le titre de l'onglet passe du début du message à un titre généré, quelques secondes après la réponse.
- [ ] Une réponse qui appelle une suite fait apparaître la pastille de suggestion ; un clic ou Tab (champ vide) l'insère ; sa croix l'ignore.

## D. Contrôles
- [ ] Changer le mode de permission **pendant** une réponse : l'effet est immédiat (sauf « Tout autoriser » : avis).
- [ ] Fermer l'onglet, « Recommencer à zéro » ou charger une session **pendant** une réponse : confirmation ; « Annuler » ne coupe rien.
- [ ] Menu du modèle : alias en premier, « Autres versions » ensuite, descriptions en info-bulle ; sur un modèle sans `xhigh`, ce niveau n'est pas proposé.
- [ ] Clic sur l'anneau de contexte : fenêtre avec la répartition ; pendant une réponse (exacte), puis plus de 3 min après (mention « estimation »).

## E. Fichiers modifiés par Claude
- [ ] Demander une modification de fichier existant et la création d'un fichier : la pastille « N fichiers modifiés +A −R » apparaît.
- [ ] Clic sur la pastille : l'arbre des changements de l'IDE s'ouvre ; double-clic ou Ctrl+D ouvre la comparaison (avant Claude / actuel) ; la navigation passe d'un fichier au suivant.
- [ ] Un fichier créé s'ouvre avec une partie gauche vide.
- [ ] Demander une commande Bash qui crée, modifie, supprime et renomme (`mv`) des fichiers : tous apparaissent.
- [ ] Modifier un fichier **à la main** dans l'éditeur : il n'apparaît pas ; modifier à la main un fichier déjà modifié par Claude entre deux réponses : il repart de zéro.
- [ ] Commit automatique ou fichier non ajouté à git : la liste est inchangée.
- [ ] « effacer » (croix de la pastille) vide la liste. Aucune erreur « Slow operations are prohibited on EDT » dans le log.

## F. Non-régression
- [ ] Approbation d'une action : boutons, « Voir le diff » modifiable, « Tout autoriser ».
- [ ] Message pendant une réponse : file d'attente et « préciser maintenant ».
- [ ] Liste des sessions : recharger, renommer, archiver ; le renommage d'un onglet apparaît aussi dans `claude --resume`.
- [ ] Clic sur un nom de fichier dans une réponse : il s'ouvre dans l'éditeur (sans erreur dans le log).
- [ ] Serveurs MCP, réglages (case « Proposer le message suivant »), batterie des quotas.
- [ ] Si configuré : accès à distance Telegram (demande d'approbation reçue et acceptée depuis le téléphone).

## G. IDE habituel (version récente)
- [ ] Installer le zip (*Plugins > ⚙ > Install Plugin from Disk*), redémarrer, refaire A, E et le clic sur un fichier.

## Contrôle du log
```
grep -n "Plugin to blame: Claude Chat\|at dev.claudechat" build/idea-sandbox/IC-2024.3/log/idea.log
```
Aucune ligne attendue. (Les erreurs `GradleJvmSupportMatrix` appartenaient à l'IDE 2024.3 ; le plugin Gradle est désactivé dans le sandbox.)
