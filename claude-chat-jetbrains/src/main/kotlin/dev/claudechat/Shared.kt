package dev.claudechat

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Lit les données communes avec l'extension VS Code (`shared/data`, copiées dans les ressources au build). */
object SharedData {
    fun read(name: String): JsonElement =
        SharedData::class.java.getResourceAsStream("/shared/$name")!!.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it) }
}

/** Constantes et textes communs aux deux éditeurs : `shared/data/constants.json` (une seule source). */
object Constants {
    private val j = SharedData.read("constants.json").asJsonObject
    private val limits = j["limits"].asJsonObject

    /** (identifiant, libellé) des modes de permission. */
    val permissionModes: List<Pair<String, String>> =
        j["permissionModes"].asJsonArray.map { it.asJsonObject["id"].asString to it.asJsonObject["label"].asString }
    /** Libellé -> explication (infobulle). */
    val permissionHelp: Map<String, String> =
        j["permissionModes"].asJsonArray.associate { it.asJsonObject["label"].asString to it.asJsonObject["help"].asString }
    val efforts: List<String> = j["efforts"].asJsonArray.map { it.asString }
    /** (identifiant, alias, nom) : modèles de secours tant que la CLI n'a pas répondu. */
    val fallbackModels: List<Triple<String, String, String>> =
        j["fallbackModels"].asJsonArray.map { it.asJsonObject.let { o -> Triple(o["id"].asString, o["alias"].asString, o["name"].asString) } }
    val defaultModel: String = j["defaultModel"].asString
    val quotaProbeModel: String = j["quotaProbeModel"].asString
    val slashPrompts: Map<String, String> = j["slashPrompts"].asJsonObject.entrySet().associate { it.key to it.value.asString }
    val commitPrompt: String = j["commitPrompt"].asString
    val generatedDirs: Set<String> = j["generatedDirs"].asJsonArray.map { it.asString }.toSet()
    val videoExtensions: Set<String> = j["videoExtensions"].asJsonArray.map { it.asString }.toSet()

    val maxAttachChars: Int = limits["maxAttachChars"].asInt
    val maxVideoFrames: Int = limits["maxVideoFrames"].asInt
    val maxVideoWidth: Int = limits["maxVideoWidth"].asInt
    val baselineMaxFileBytes: Long = limits["baselineMaxFileBytes"].asLong
    val baselineMaxTotalBytes: Long = limits["baselineMaxTotalBytes"].asLong
    val baselineMaxEntries: Int = limits["baselineMaxEntries"].asInt
    val commitDiffMaxChars: Int = limits["commitDiffMaxChars"].asInt
    val mcpWaitMs: Long = limits["mcpWaitMs"].asLong
    val pairingTtlMs: Long = limits["pairingTtlMs"].asLong
    val maxPairingFailures: Int = limits["maxPairingFailures"].asInt
    val quietBeforeCloseMs: Long = limits["quietBeforeCloseMs"].asLong
    val maxAutoResumes: Int = limits["maxAutoResumes"].asInt
    val quotaStaleMs: Long = limits["quotaStaleMs"].asLong
}
