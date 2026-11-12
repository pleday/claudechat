# Commandes courantes ; tout passe par le wrapper Gradle (aucune installation de Gradle requise).
GRADLE := ./gradlew

.PHONY: help build test plugin run verify clean

help: ## Liste des commandes
	@grep -E '^[a-z]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/ —/'

build: ## Compile le code
	$(GRADLE) compileKotlin

test: ## Lance les tests unitaires
	$(GRADLE) test

plugin: test ## Construit le plugin installable : build/distributions/*.zip
	$(GRADLE) buildPlugin
	@ls -1 build/distributions/*.zip

run: ## Lance un IDE de test avec le plugin
	$(GRADLE) runIde

verify: ## Vérifie la structure du plugin (plugin.xml, compatibilité)
	$(GRADLE) verifyPluginProjectConfiguration

clean: ## Supprime les fichiers générés
	$(GRADLE) clean
