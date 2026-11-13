plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.claudechat"
version = "0.8.4"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        pluginVerifier()
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

intellijPlatform {
    // Évite de lancer un second IDE pendant la construction (impossible si `make run` est ouvert) ;
    // seul l'index de la recherche des réglages en dépend.
    buildSearchableOptions = false
    // Compatibilité sur les IDE récents (untilBuild est ouvert : l'API interne utilisée peut casser) : `./gradlew verifyPlugin`
    pluginVerification {
        ides { recommended() }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}
