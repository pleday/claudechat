package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Faux serveur de l'API Bot Telegram : les mises à jour sont poussées par le test. */
class RemoteHubTest {
    private lateinit var server: HttpServer
    private val updates = LinkedBlockingQueue<JsonObject>()
    private val sent = CopyOnWriteArrayList<JsonObject>()
    private val edits = CopyOnWriteArrayList<JsonObject>()
    private val callbackAnswers = CopyOnWriteArrayList<JsonObject>()
    private var nextId = 100
    private var updateId = 1
    private lateinit var hub: RemoteHub
    private val paired = LinkedBlockingQueue<Long>()
    @Volatile private var clockMs = 0L

    @BeforeEach fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = JsonParser.parseString(ex.requestBody.readAllBytes().toString(Charsets.UTF_8)).asJsonObject
            val method = ex.requestURI.path.substringAfterLast('/')
            val result: Any = when (method) {
                "getUpdates" -> {
                    val first = updates.poll(200, TimeUnit.MILLISECONDS)
                    JsonArray().also { a -> first?.let { a.add(it) } }
                }
                "sendMessage" -> { sent += body; JsonObject().apply { addProperty("message_id", nextId++) } }
                "editMessageText" -> { edits += body; true }
                "answerCallbackQuery" -> { callbackAnswers += body; true }
                else -> true
            }
            val resp = JsonObject().apply {
                addProperty("ok", true)
                when (result) { is JsonArray -> add("result", result); is JsonObject -> add("result", result); else -> addProperty("result", true) }
            }.toString().toByteArray()
            ex.responseHeaders.add("content-type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong()); ex.responseBody.use { it.write(resp) }
        }
        server.start()
        hub = RemoteHub()
        hub.bot = TelegramBot("TOK", 42L, hub, "http://127.0.0.1:${server.address.port}", pollTimeoutSec = 0, clock = { clockMs })
        hub.onPairedCallback = { paired.add(it) }
        hub.start()
    }

    @AfterEach fun tearDown() { hub.stop(); server.stop(0) }

    private fun msg(chat: Long, text: String, replyTo: Int? = null) = JsonObject().apply {
        addProperty("update_id", updateId++)
        add("message", JsonObject().apply {
            add("chat", JsonObject().apply { addProperty("id", chat) }); addProperty("text", text)
            replyTo?.let { add("reply_to_message", JsonObject().apply { addProperty("message_id", it) }) }
        })
    }

    private class FakeSink : RemoteHub.Sink {
        val got = LinkedBlockingQueue<String>()
        override fun onPhoneMessage(text: String) { got.add(text) }
    }

    private fun click(chat: Long, data: String) = JsonObject().apply {
        addProperty("update_id", updateId++)
        add("callback_query", JsonObject().apply {
            addProperty("id", "cb1"); addProperty("data", data)
            add("message", JsonObject().apply { add("chat", JsonObject().apply { addProperty("id", chat) }); addProperty("message_id", 100) })
        })
    }

    private fun await(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(cond(), "condition non atteinte")
    }

    private fun lastButtons(): List<Pair<String, String>> =
        sent.last()["reply_markup"].asJsonObject["inline_keyboard"].asJsonArray.map {
            val b = it.asJsonArray[0].asJsonObject; b["text"].asString to b["callback_data"].asString
        }

    @Test fun permissionButtonResolvesAndEditsMessage() {
        val answers = LinkedBlockingQueue<String>()
        hub.offer("Bash — ls", "ls -la", listOf("Autoriser", "Refuser"), false) { answers.add(it) }
        assertTrue("ls -la" in sent.last()["text"].asString)
        val btn = lastButtons().first { it.first == "Refuser" }
        updates.add(click(42, btn.second))
        assertEquals("Refuser", answers.poll(5, TimeUnit.SECONDS))
        await { edits.isNotEmpty() }
        assertTrue("Refuser" in edits.last()["text"].asString)
        await { callbackAnswers.isNotEmpty() }
    }

    @Test fun freeTextAnswersQuestion() {
        val answers = LinkedBlockingQueue<String>()
        hub.offer("Question", "Quelle couleur ?", listOf("Rouge"), true) { answers.add(it) }
        updates.add(msg(42, "Bleu"))
        assertEquals("Bleu", answers.poll(5, TimeUnit.SECONDS))
    }

    @Test fun ignoresOtherChats() {
        val answers = LinkedBlockingQueue<String>()
        hub.offer("Q", "", listOf("Oui"), true) { answers.add(it) }
        val btn = lastButtons().first().second
        updates.add(click(999, btn)); updates.add(msg(999, "intrus"))
        assertNull(answers.poll(800, TimeUnit.MILLISECONDS))
    }

    @Test fun localAnswerWinsAndCancelsRemote() {
        val answers = LinkedBlockingQueue<String>()
        val h = hub.offer("Q", "", listOf("Oui"), false) { answers.add(it) }!!
        assertTrue(h.tryClaim())
        h.done("l'IDE", "Oui")
        await { edits.isNotEmpty() && "l'IDE" in edits.last()["text"].asString }
        updates.add(click(42, lastButtons().first().second))
        assertNull(answers.poll(800, TimeUnit.MILLISECONDS), "le clic tardif ne doit rien déclencher")
        assertFalse(h.tryClaim())
    }

    @Test fun remoteAnswerBlocksLocalClaim() {
        val answers = LinkedBlockingQueue<String>()
        val h = hub.offer("Q", "", listOf("Oui"), false) { answers.add(it) }!!
        updates.add(click(42, lastButtons().first().second))
        assertEquals("Oui", answers.poll(5, TimeUnit.SECONDS))
        assertFalse(h.tryClaim())
    }

    @Test fun pairingWithCorrectCodeBindsChat() {
        hub.bot.chatId = null
        hub.bot.pairingCode = "123456"
        updates.add(msg(7, "/pair 000000"))
        await { sent.isNotEmpty() }
        assertTrue("invalide" in sent.last()["text"].asString)
        updates.add(msg(7, "/pair 123456"))
        assertEquals(7L, paired.poll(5, TimeUnit.SECONDS))
        assertEquals(7L, hub.bot.chatId)
        assertNull(hub.bot.pairingCode, "code à usage unique")
    }

    @Test fun pairingCodeExpiresAfterFiveMinutes() {
        hub.bot.chatId = null
        hub.bot.pairingCode = "123456"
        clockMs += 5 * 60_000L + 1
        updates.add(msg(7, "/pair 123456"))
        await { sent.isNotEmpty() }
        assertTrue("expiré" in sent.last()["text"].asString)
        assertNull(paired.poll(300, TimeUnit.MILLISECONDS))
        assertNull(hub.bot.chatId)
        assertNull(hub.bot.pairingCode)
    }

    @Test fun pairingCodeIsRevokedAfterTooManyWrongAttempts() {
        hub.bot.chatId = null
        hub.bot.pairingCode = "123456"
        repeat(5) { updates.add(msg(7, "/pair 00000$it")) }
        await { sent.size >= 5 }
        assertNull(hub.bot.pairingCode)
        updates.add(msg(7, "/pair 123456"))
        await { sent.size >= 6 }
        assertNull(paired.poll(300, TimeUnit.MILLISECONDS), "le bon code ne doit plus fonctionner")
        assertNull(hub.bot.chatId)
    }

    @Test fun noOfferWhenUnpaired() {
        hub.bot.chatId = null
        assertNull(hub.offer("Q", "", listOf("Oui"), false) {})
    }

    @Test fun textWithoutAnyConversationIsAnswered() {
        updates.add(msg(42, "salut"))
        await { sent.any { "Aucune conversation" in it["text"].asString } }
    }

    @Test fun replyAfterFinalMessageGoesBackToTheConversation() {
        val tab = FakeSink(); hub.register(tab)
        hub.say(tab, "J'ai terminé. Veux-tu que je continue ?")
        assertTrue("terminé" in sent.last()["text"].asString)
        updates.add(msg(42, "oui, continue"))
        assertEquals("oui, continue", tab.got.poll(5, TimeUnit.SECONDS))
    }

    @Test fun replyToRoutesToTheRightTab() {
        val a = FakeSink(); val b = FakeSink(); hub.register(a); hub.register(b)
        hub.say(a, "message de A")          // id 100
        val idA = nextId - 1
        hub.say(b, "message de B")          // dernier actif = B
        updates.add(msg(42, "pour A", replyTo = idA))
        assertEquals("pour A", a.got.poll(5, TimeUnit.SECONDS))
        updates.add(msg(42, "sans reply"))
        assertEquals("sans reply", a.got.poll(5, TimeUnit.SECONDS), "sans « répondre » : l'onglet avec lequel on vient d'échanger")
        assertNull(b.got.poll(300, TimeUnit.MILLISECONDS))
        hub.say(b, "B reprend la parole")
        updates.add(msg(42, "maintenant B"))
        assertEquals("maintenant B", b.got.poll(5, TimeUnit.SECONDS))
    }

    @Test fun pendingQuestionTakesPrecedenceOverLastActiveTab() {
        val tab = FakeSink(); hub.register(tab)
        hub.say(tab, "fini")
        val answers = LinkedBlockingQueue<String>()
        hub.offer("Q", "Quelle couleur ?", listOf("Rouge"), true) { answers.add(it) }
        updates.add(msg(42, "Bleu"))
        assertEquals("Bleu", answers.poll(5, TimeUnit.SECONDS))
        assertNull(tab.got.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun unregisteredTabNoLongerReceives() {
        val tab = FakeSink(); hub.register(tab)
        hub.say(tab, "fini"); hub.unregister(tab)
        updates.add(msg(42, "tard"))
        assertNull(tab.got.poll(500, TimeUnit.MILLISECONDS))
        await { sent.any { "Aucune conversation" in it["text"].asString } }
    }

    @Test fun longAnswerIsSplitIntoSeveralMessages() {
        val tab = FakeSink(); hub.register(tab)
        val before = sent.size
        hub.say(tab, (1..400).joinToString("\n") { "ligne $it avec un peu de texte pour remplir" })
        assertTrue(sent.size - before >= 2)
        assertTrue(sent.drop(before).all { it["text"].asString.length <= 3900 })
    }

    @Test fun describeToolShowsEditDiff() {
        val i = JsonParser.parseString("""{"file_path":"/a.kt","old_string":"x","new_string":"y"}""").asJsonObject
        assertEquals("Fichier : /a.kt\n- x\n+ y", describeTool("Edit", i))
    }
}
