import { randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { chunkText } from './textTools';
import type { Json } from './cli';

export interface TelegramHandler {
  onCallback(data: string): void;
  onText(text: string, replyTo?: number): void;
  onPaired(chatId: number): void;
}

const PAIRING_TTL_MS = 5 * 60_000;
const MAX_PAIRING_FAILURES = 5;

/**
 * Client minimal de l'API Bot de Telegram en long polling : aucune connexion entrante, aucun tunnel.
 * Seul le chat appairé (chatId) est écouté ; tout autre expéditeur est ignoré.
 */
export class TelegramBot {
  private running = false;
  private abort?: AbortController;
  private pairingExpiresAt = 0;
  private pairingFailures = 0;
  private _pairingCode?: string;

  constructor(
    private token: string,
    public chatId: number | undefined,
    private handler: TelegramHandler,
    private baseUrl = 'https://api.telegram.org',
    private pollTimeoutSec = 25,
    private clock: () => number = Date.now,
  ) {}

  /**
   * Code d'appairage à usage unique : le message `/pair <code>` du téléphone enregistre son chat.
   * Il expire après 5 minutes et est révoqué après 5 essais erronés (6 chiffres seulement : sans cela, quiconque
   * connaît le bot pourrait les tester tous).
   */
  get pairingCode(): string | undefined { return this._pairingCode; }
  set pairingCode(v: string | undefined) { this._pairingCode = v; this.pairingFailures = 0; this.pairingExpiresAt = this.clock() + PAIRING_TTL_MS; }

  start(): void {
    if (this.running) return;
    this.running = true;
    void this.loop();
  }

  stop(): void { this.running = false; this.abort?.abort(); }

  private async call(method: string, body: Json, timeoutSec = 15): Promise<Json | undefined> {
    const ctl = new AbortController();
    this.abort = method === 'getUpdates' ? ctl : this.abort;
    const timer = setTimeout(() => ctl.abort(), timeoutSec * 1000);
    try {
      const resp = await fetch(`${this.baseUrl}/bot${this.token}/${method}`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body), signal: ctl.signal,
      });
      const j = (await resp.json().catch(() => undefined)) as Json | undefined;
      return resp.status === 200 && j?.ok === true ? j : undefined;
    } finally { clearTimeout(timer); }
  }

  /** Envoie un message avec des boutons (libellé, donnée de rappel) ; renvoie l'identifiant du message. */
  async send(text: string, buttons: Array<[string, string]> = [], to: number | undefined = this.chatId): Promise<number | undefined> {
    if (to === undefined) return undefined;
    try {
      const body: Json = { chat_id: to, text: text.slice(0, 3900) };
      if (buttons.length) body.reply_markup = { inline_keyboard: buttons.map(([label, data]) => [{ text: label, callback_data: data }]) };
      const r = await this.call('sendMessage', body);
      return r?.result?.message_id;
    } catch { return undefined; }
  }

  /** Remplace le texte d'un message et retire ses boutons. */
  async edit(messageId: number, text: string): Promise<void> {
    if (this.chatId === undefined) return;
    try { await this.call('editMessageText', { chat_id: this.chatId, message_id: messageId, text: text.slice(0, 3900) }); } catch { /* sans importance */ }
  }

  private async loop(): Promise<void> {
    let offset = 0;
    while (this.running) {
      try {
        const res = await this.call('getUpdates', { offset, timeout: this.pollTimeoutSec }, this.pollTimeoutSec + 15);
        if (!res) { if (this.running) await new Promise((r) => setTimeout(r, 3000)); continue; }
        for (const u of res.result as Json[]) {
          offset = u.update_id + 1;
          try { await this.handle(u); } catch { /* un message illisible n'arrête pas l'écoute */ }
        }
      } catch {
        if (this.running) await new Promise((r) => setTimeout(r, 3000));
      }
    }
  }

  async handle(u: Json): Promise<void> {
    const cq = u.callback_query;
    if (cq) {
      const chat = cq.message?.chat?.id;
      if (chat === undefined || chat !== this.chatId) return;
      try { await this.call('answerCallbackQuery', { callback_query_id: cq.id }); } catch { /* sans importance */ }
      if (typeof cq.data === 'string') this.handler.onCallback(cq.data);
      return;
    }
    const msg = u.message;
    if (!msg) return;
    const chat: number = msg.chat.id;
    const text = msg.text;
    if (typeof text !== 'string') return;
    if (text.startsWith('/pair')) {
      if (this._pairingCode && this.clock() > this.pairingExpiresAt) this._pairingCode = undefined;
      const live = this._pairingCode;
      const given = text.slice('/pair'.length).trim();
      const a = Buffer.from(live ?? ''); const b = Buffer.from(given);
      if (live && a.length === b.length && timingSafeEqual(a, b)) {
        this._pairingCode = undefined; this.chatId = chat;
        this.handler.onPaired(chat);
        await this.send("✅ Appairé avec Claude Chat. Les demandes d'approbation et questions arriveront ici.", [], chat);
      } else {
        if (live && ++this.pairingFailures >= MAX_PAIRING_FAILURES) this._pairingCode = undefined;
        await this.send("Code d'appairage invalide ou expiré.", [], chat);
      }
      return;
    }
    if (chat === this.chatId) this.handler.onText(text, msg.reply_to_message?.message_id);
  }
}

export interface RemoteHandle {
  /** Revendique la réponse ; false si le téléphone a déjà répondu. */
  tryClaim(): boolean;
  done(by: string, choice: string): void;
  cancel(reason: string): void;
}

/** Une conversation (onglet) qui échange des messages libres avec le téléphone. */
export interface RemoteSink { onPhoneMessage(text: string): void }

interface Pending { title: string; options: string[]; freeText: boolean; onAnswer: (choice: string) => void; messageId?: number }

/** Gère les demandes envoyées au téléphone : la première réponse (IDE ou téléphone) l'emporte. */
export class RemoteHub implements TelegramHandler {
  private sinks = new Set<RemoteSink>();
  private owners = new Map<number, RemoteSink>();
  private lastActive?: RemoteSink;
  private pendings = new Map<string, Pending>();
  onPairedCallback: (chatId: number) => void = () => {};

  constructor(public bot: TelegramBot) {}

  start(): void { this.bot.start(); }
  stop(): void { this.bot.stop(); }
  get paired(): boolean { return this.bot.chatId !== undefined; }

  register(s: RemoteSink): void { this.sinks.add(s); }
  unregister(s: RemoteSink): void {
    this.sinks.delete(s);
    if (this.lastActive === s) this.lastActive = undefined;
    for (const [k, v] of this.owners) if (v === s) this.owners.delete(k);
  }

  /** Envoie un message libre de la part d'une conversation ; une réponse (ou « répondre ») y sera routée. */
  async say(sink: RemoteSink, text: string): Promise<void> {
    if (!this.paired) return;
    for (const part of chunkText(text)) {
      const id = await this.bot.send(part);
      if (id !== undefined) { this.owners.set(id, sink); if (this.owners.size > 300) this.owners.delete(this.owners.keys().next().value!); }
      this.lastActive = sink;
    }
  }

  send(text: string): void { if (this.paired) void this.bot.send(text); }

  /** Envoie la demande ; undefined si aucun téléphone n'est appairé. */
  offer(title: string, body: string, options: string[], freeText: boolean, onAnswer: (choice: string) => void): RemoteHandle | undefined {
    if (!this.paired) return undefined;
    const id = randomBytes(3).toString('hex');
    const p: Pending = { title, options, freeText, onAnswer };
    this.pendings.set(id, p);
    let text = `🔔 ${title}`;
    if (body.trim()) text += `\n\n${body.slice(0, 3000)}`;
    if (freeText) text += '\n\n✍️ Réponds par un message pour une réponse libre.';
    void this.bot.send(text, options.map((o, i): [string, string] => [o, `${id}:${i}`])).then((m) => { p.messageId = m; });
    const claim = () => this.pendings.delete(id);
    return {
      tryClaim: claim,
      done: (by, choice) => { if (p.messageId !== undefined) void this.bot.edit(p.messageId, `✅ ${title}\n→ ${choice} (depuis ${by})`); },
      cancel: (reason) => { if (claim() && p.messageId !== undefined) void this.bot.edit(p.messageId, `⏹ ${title}\n(${reason})`); },
    };
  }

  onCallback(data: string): void {
    const id = data.split(':')[0];
    const idx = Number.parseInt(data.includes(':') ? data.slice(data.indexOf(':') + 1) : '', 10);
    if (Number.isNaN(idx)) return;
    const p = this.pendings.get(id);
    if (!p) return;
    this.pendings.delete(id);
    const choice = p.options[idx];
    if (choice === undefined) return;
    if (p.messageId !== undefined) void this.bot.edit(p.messageId, `✅ ${p.title}\n→ ${choice} (depuis le téléphone)`);
    try { p.onAnswer(choice); } catch { /* sans importance */ }
  }

  onText(text: string, replyTo?: number): void {
    // 1) réponse explicite à un message d'une conversation ; 2) question en attente ; 3) dernière conversation active
    let target: RemoteSink | undefined;
    let pending: Pending | undefined;
    const byReply = replyTo !== undefined ? this.owners.get(replyTo) : undefined;
    if (byReply) target = byReply;
    else {
      const last = [...this.pendings.entries()].reverse().find(([, v]) => v.freeText);
      if (last) { this.pendings.delete(last[0]); pending = last[1]; }
      else target = this.lastActive && this.sinks.has(this.lastActive) ? this.lastActive : this.sinks.size === 1 ? [...this.sinks][0] : undefined;
    }
    if (pending) {
      if (pending.messageId !== undefined) void this.bot.edit(pending.messageId, `✅ ${pending.title}\n→ ${text.slice(0, 200)} (depuis le téléphone)`);
      try { pending.onAnswer(text); } catch { /* sans importance */ }
      return;
    }
    if (!target) { void this.bot.send("Aucune conversation à distance active (active l'interrupteur 📱 dans l'IDE)."); return; }
    this.lastActive = target;
    try { target.onPhoneMessage(text); } catch { /* sans importance */ }
  }

  onPaired(chatId: number): void { this.onPairedCallback(chatId); }

  /** Code d'appairage à 6 chiffres. */
  newPairingCode(): string {
    const code = String(randomInt(100000, 1000000));
    this.bot.pairingCode = code;
    return code;
  }
}

/** Point d'accès unique, configuré depuis les réglages (plusieurs conversations partagent le même bot). */
export class RemoteBridge {
  private hub?: RemoteHub;
  private sinks = new Set<RemoteSink>();

  /** Relit les réglages : coupe l'ancien bot et en démarre un nouveau si c'est activé et si un jeton est fourni. */
  reconfigure(cfg: { enabled: boolean; token?: string; chatId?: string; onPaired: (chatId: number) => void }): void {
    this.hub?.stop(); this.hub = undefined;
    if (!cfg.enabled || !cfg.token?.trim()) return;
    const id = Number.parseInt(cfg.chatId ?? '', 10);
    const bot = new TelegramBot(cfg.token.trim(), Number.isNaN(id) ? undefined : id, { onCallback: () => {}, onText: () => {}, onPaired: () => {} });
    const hub = new RemoteHub(bot);
    (bot as unknown as { handler: TelegramHandler }).handler = hub;
    hub.onPairedCallback = cfg.onPaired;
    this.sinks.forEach((s) => hub.register(s));
    hub.start();
    this.hub = hub;
  }

  offer(title: string, body: string, options: string[], freeText: boolean, onAnswer: (c: string) => void): RemoteHandle | undefined {
    return this.hub?.offer(title, body, options, freeText, onAnswer);
  }
  notifyPhone(text: string): void { this.hub?.send(text); }

  /** Les conversations inscrites survivent à une reconfiguration du bot. */
  register(s: RemoteSink): void { this.sinks.add(s); this.hub?.register(s); }
  unregister(s: RemoteSink): void { this.sinks.delete(s); this.hub?.unregister(s); }
  say(s: RemoteSink, text: string): void { void this.hub?.say(s, text); }

  /** Génère un code d'appairage ; undefined si le bot n'est pas actif. */
  newPairingCode(): string | undefined { return this.hub?.newPairingCode(); }
  isPaired(): boolean { return this.hub?.paired === true; }
  isActive(): boolean { return this.hub !== undefined; }
}
