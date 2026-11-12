package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Client minimal de l'API Bot de Telegram en long polling : aucune connexion entrante, aucun tunnel.
 * Seul le chat appairé (chatId) est écouté ; tout autre expéditeur est ignoré.
 */
class TelegramBot(
    private val token: String,
    @Volatile var chatId: Long?,
    private val handler: Handler,
    private val baseUrl: String = "https://api.telegram.org",
    private val pollTimeoutSec: Int = 25,
) {
    interface Handler {
        fun onCallback(data: String)
        fun onText(text: String, replyTo: Int?)
        fun onPaired(chatId: Long)
    }

    /** Code d'appairage à usage unique : le message `/pair <code>` du téléphone enregistre son chat. */
    @Volatile var pairingCode: String? = null

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "claude-telegram").apply { isDaemon = true; start() }
    }

    fun stop() { running = false; thread?.interrupt() }

    private fun call(method: String, body: JsonObject, timeoutSec: Long = 15): JsonObject? {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/bot$token/$method"))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        val j = runCatching { JsonParser.parseString(resp.body()).asJsonObject }.getOrNull()
        return if (resp.statusCode() == 200 && j?.get("ok")?.asBoolean == true) j else null
    }

    /** Envoie un message avec des boutons (libellé, donnée de rappel) ; renvoie l'identifiant du message. */
    fun send(text: String, buttons: List<Pair<String, String>> = emptyList(), to: Long? = chatId): Int? = runCatching {
        val body = JsonObject().apply {
            addProperty("chat_id", to ?: return null)
            addProperty("text", text.take(3900))
            if (buttons.isNotEmpty()) add("reply_markup", JsonObject().apply {
                add("inline_keyboard", JsonArray().also { rows ->
                    buttons.forEach { (label, data) ->
                        rows.add(JsonArray().apply { add(JsonObject().apply { addProperty("text", label); addProperty("callback_data", data) }) })
                    }
                })
            })
        }
        call("sendMessage", body)?.get("result")?.asJsonObject?.get("message_id")?.asInt
    }.getOrNull()

    /** Remplace le texte d'un message et retire ses boutons. */
    fun edit(messageId: Int, text: String) {
        runCatching {
            call("editMessageText", JsonObject().apply {
                addProperty("chat_id", chatId ?: return); addProperty("message_id", messageId); addProperty("text", text.take(3900))
            })
        }
    }

    private fun loop() {
        var offset = 0L
        while (running) {
            try {
                val res = call("getUpdates", JsonObject().apply {
                    addProperty("offset", offset); addProperty("timeout", pollTimeoutSec)
                }, pollTimeoutSec + 15L)
                    ?: run { Thread.sleep(3000); null } ?: continue
                for (u in res["result"].asJsonArray) {
                    val uo = u.asJsonObject
                    offset = uo["update_id"].asLong + 1
                    runCatching { handle(uo) }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                if (running) runCatching { Thread.sleep(3000) }
            }
        }
    }

    private fun handle(u: JsonObject) {
        u["callback_query"]?.asJsonObject?.let { cq ->
            val chat = cq["message"]?.asJsonObject?.get("chat")?.asJsonObject?.get("id")?.asLong
            if (chat == null || chat != chatId) return
            runCatching { call("answerCallbackQuery", JsonObject().apply { addProperty("callback_query_id", cq["id"].asString) }) }
            cq["data"]?.asString?.let(handler::onCallback)
            return
        }
        val msg = u["message"]?.asJsonObject ?: return
        val chat = msg["chat"].asJsonObject["id"].asLong
        val text = msg["text"]?.asString ?: return
        val code = pairingCode
        if (text.startsWith("/pair")) {
            if (code != null && text.removePrefix("/pair").trim() == code) {
                pairingCode = null; chatId = chat
                handler.onPaired(chat)
                send("✅ Appairé avec Claude Chat. Les demandes d'approbation et questions arriveront ici.", to = chat)
            } else send("Code d'appairage invalide ou expiré.", to = chat)
            return
        }
        if (chat == chatId) handler.onText(text, msg["reply_to_message"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("message_id")?.asInt)
    }
}

/** Gère les demandes envoyées au téléphone : la première réponse (IDE ou téléphone) l'emporte. */
class RemoteHub : TelegramBot.Handler {
    interface Handle {
        /** Revendique la réponse ; false si le téléphone a déjà répondu. */
        fun tryClaim(): Boolean
        fun done(by: String, choice: String)
        fun cancel(reason: String)
    }

    private class Pending(val title: String, val options: List<String>, val freeText: Boolean, val onAnswer: (String) -> Unit) {
        @Volatile var messageId: Int? = null
    }

    /** Une conversation (onglet) qui échange des messages libres avec le téléphone. */
    interface Sink { fun onPhoneMessage(text: String) }

    private val sinks = LinkedHashSet<Sink>()
    private val owners = object : LinkedHashMap<Int, Sink>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Sink>?) = size > 300
    }
    @Volatile private var lastActive: Sink? = null

    fun register(s: Sink) { synchronized(pendings) { sinks += s } }
    fun unregister(s: Sink) { synchronized(pendings) { sinks -= s; if (lastActive === s) lastActive = null; owners.values.removeAll { it === s } } }

    /** Envoie un message libre de la part d'une conversation ; une réponse (ou « répondre ») y sera routée. */
    fun say(sink: Sink, text: String) {
        if (!paired) return
        for (part in chunkText(text)) {
            val id = bot.send(part)
            synchronized(pendings) { if (id != null) owners[id] = sink; lastActive = sink }
        }
    }

    lateinit var bot: TelegramBot
    var onPairedCallback: (Long) -> Unit = {}
    private val pendings = LinkedHashMap<String, Pending>()

    fun start() = bot.start()
    fun stop() { bot.stop() }

    val paired: Boolean get() = bot.chatId != null

    fun send(text: String) { if (paired) bot.send(text) }

    /** Envoie la demande ; null si aucun téléphone n'est appairé. */
    fun offer(title: String, body: String, options: List<String>, freeText: Boolean, onAnswer: (String) -> Unit): Handle? {
        if (!paired) return null
        val id = UUID.randomUUID().toString().take(6)
        val p = Pending(title, options, freeText, onAnswer)
        synchronized(pendings) { pendings[id] = p }
        val text = buildString {
            append("🔔 ").append(title)
            if (body.isNotBlank()) append("\n\n").append(body.take(3000))
            if (freeText) append("\n\n✍️ Réponds par un message pour une réponse libre.")
        }
        p.messageId = bot.send(text, options.mapIndexed { i, o -> o to "$id:$i" })
        return object : Handle {
            private fun claim() = synchronized(pendings) { pendings.remove(id) } != null
            override fun tryClaim() = claim()
            override fun done(by: String, choice: String) { p.messageId?.let { bot.edit(it, "✅ $title\n→ $choice (depuis $by)") } }
            override fun cancel(reason: String) { if (claim()) p.messageId?.let { bot.edit(it, "⏹ $title\n($reason)") } }
        }
    }

    override fun onCallback(data: String) {
        val id = data.substringBefore(':')
        val idx = data.substringAfter(':', "").toIntOrNull() ?: return
        val p = synchronized(pendings) { pendings.remove(id) } ?: return
        val choice = p.options.getOrNull(idx) ?: return
        p.messageId?.let { bot.edit(it, "✅ ${p.title}\n→ $choice (depuis le téléphone)") }
        runCatching { p.onAnswer(choice) }
    }

    override fun onText(text: String, replyTo: Int?) {
        // 1) réponse explicite à un message d'une conversation ; 2) question en attente ; 3) dernière conversation active
        val target: Sink?
        var pendingEntry: Map.Entry<String, Pending>? = null
        synchronized(pendings) {
            val byReply = replyTo?.let { owners[it] }
            if (byReply != null) target = byReply
            else {
                pendingEntry = pendings.entries.lastOrNull { it.value.freeText }?.also { pendings.remove(it.key) }
                target = if (pendingEntry != null) null else (lastActive?.takeIf { it in sinks } ?: sinks.singleOrNull())
            }
        }
        pendingEntry?.value?.let { p ->
            p.messageId?.let { bot.edit(it, "✅ ${p.title}\n→ ${text.take(200)} (depuis le téléphone)") }
            runCatching { p.onAnswer(text) }
            return
        }
        if (target == null) { bot.send("Aucune conversation à distance active (active l'interrupteur 📱 dans l'IDE)."); return }
        synchronized(pendings) { lastActive = target }
        runCatching { target.onPhoneMessage(text) }
    }

    override fun onPaired(chatId: Long) = onPairedCallback(chatId)
}

/** Point d'accès unique, configuré depuis les réglages. */
object RemoteBridge {
    @Volatile private var hub: RemoteHub? = null
    private val sinks = LinkedHashSet<RemoteHub.Sink>()
    @Volatile private var started = false

    /** Démarre une seule fois par IDE (plusieurs onglets partagent le même bot). */
    fun ensureStarted() { if (!started) reconfigure() }

    /** Relit les réglages (jeton dans le coffre : à appeler hors EDT). */
    @Synchronized fun reconfigure() {
        started = true
        hub?.stop(); hub = null
        val s = ClaudeSettings.get()
        if (!s.state.remoteEnabled) return
        val token = s.telegramToken?.takeIf { it.isNotBlank() } ?: return
        val h = RemoteHub()
        h.bot = TelegramBot(token, s.state.telegramChatId.toLongOrNull(), h)
        h.onPairedCallback = { s.state.telegramChatId = it.toString() }
        synchronized(sinks) { sinks.forEach(h::register) }
        h.start()
        hub = h
    }

    fun offer(title: String, body: String, options: List<String>, freeText: Boolean, onAnswer: (String) -> Unit): RemoteHub.Handle? =
        hub?.offer(title, body, options, freeText, onAnswer)

    fun notifyPhone(text: String) { hub?.send(text) }

    /** Les conversations inscrites survivent à une reconfiguration du bot. */
    fun register(s: RemoteHub.Sink) { synchronized(sinks) { sinks += s }; hub?.register(s) }
    fun unregister(s: RemoteHub.Sink) { synchronized(sinks) { sinks -= s }; hub?.unregister(s) }
    fun say(s: RemoteHub.Sink, text: String) { hub?.say(s, text) }

    /** Génère un code d'appairage ; null si le bot n'est pas actif. */
    fun newPairingCode(): String? {
        val h = hub ?: return null
        val code = (100000 + java.security.SecureRandom().nextInt(900000)).toString()
        h.bot.pairingCode = code
        return code
    }

    fun isPaired() = hub?.paired == true
}
