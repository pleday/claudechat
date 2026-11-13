import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.claudechat"
version = "0.9.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        pluginVerifier()
        // arbre des changements de l'IDE (SimpleChangesBrowser) pour la liste des fichiers modifiés par Claude
        bundledModule("intellij.platform.vcs.impl")
    }
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.22.0")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

kotlin { jvmToolchain(21) }

// IDE de test (`make run`) : l'IDE 2024.3 plante au démarrage dans son plugin Gradle (« IllegalArgumentException: 25 »). Il relit
// les versions de Java récentes qu'il a téléchargées (jusqu'à 26) et ne sait plus les analyser. Claude Chat n'utilise pas Gradle :
// on désactive ce plugin (et ceux qui en dépendent) dans le sandbox seulement, jamais dans l'IDE de l'utilisateur.
tasks.prepareSandbox {
    doLast {
        sandboxConfigDirectory.get().asFile.resolve("disabled_plugins.txt").apply {
            parentFile.mkdirs()
            writeText(
                listOf(
                    "org.jetbrains.plugins.gradle", "org.jetbrains.plugins.gradle.analysis",
                    "org.jetbrains.plugins.gradle.dependency.updater", "org.jetbrains.plugins.gradle.maven",
                ).joinToString("\n", postfix = "\n"),
            )
        }
    }
}

intellijPlatform {
    // Évite de lancer un second IDE pendant la construction (impossible si `make run` est ouvert) ;
    // seul l'index de la recherche des réglages en dépend.
    buildSearchableOptions = false
    // Compatibilité sur les IDE récents (untilBuild est ouvert : l'API interne utilisée peut casser) : `./gradlew verifyPlugin`
    pluginVerification {
        // Liste explicite : recommended() choisit IntelliJ IDEA Community 2025.3, qui n'est plus publiée (IDE unifié depuis cette version).
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
        }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}
