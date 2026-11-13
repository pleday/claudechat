import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { RemoteHub, TelegramBot, type RemoteSink, type TelegramHandler } from '../src/core/remote';

/** Faux bot : enregistre ce qui serait envoyé à Telegram. */
class FakeBot extends TelegramBot {
  sent: Array<{ text: string; buttons: Array<[string, string]> }> = [];
  edits: Array<{ id: number; text: string }> = [];
  private n = 100;
  constructor(chatId: number | undefined) { super('t', chatId, { onCallback() {}, onText() {}, onPaired() {} }); }
  override async send(text: string, buttons: Array<[string, string]> = []): Promise<number | undefined> { this.sent.push({ text, buttons }); return ++this.n; }
  override async edit(id: number, text: string): Promise<void> { this.edits.push({ id, text }); }
  override start(): void {}
}

const hub = (chat: number | null = 1) => { const bot = new FakeBot(chat ?? undefined); return { bot, hub: new RemoteHub(bot) }; };
const flush = () => new Promise((r) => setTimeout(r, 10));

describe('RemoteHub : la première réponse l\'emporte', () => {
  it('sans téléphone appairé, aucune demande', () => {
    expect(hub(null).hub.offer('t', 'b', ['Oui'], false, () => {})).toBeUndefined();
  });

  it('réponse par bouton depuis le téléphone', async () => {
    const { bot, hub: h } = hub();
    const answers: string[] = [];
    const handle = h.offer('Autoriser ?', 'rm -rf', ['Autoriser', 'Refuser'], false, (c) => answers.push(c))!;
    await flush();
    expect(bot.sent[0].text).toContain('Autoriser ?');
    const [, data] = bot.sent[0].buttons[1];
    h.onCallback(data);
    h.onCallback(data); // second clic : ignoré
    expect(answers).toEqual(['Refuser']);
    expect(handle.tryClaim()).toBe(false); // le téléphone a déjà répondu
    expect(bot.edits[0].text).toContain('Refuser');
  });

  it('réponse depuis l\'IDE : le téléphone ne peut plus répondre', async () => {
    const { bot, hub: h } = hub();
    const answers: string[] = [];
    const handle = h.offer('Q', '', ['A', 'B'], false, (c) => answers.push(c))!;
    await flush();
    expect(handle.tryClaim()).toBe(true);
    handle.done("l'IDE", 'A');
    h.onCallback(bot.sent[0].buttons[1][1]);
    expect(answers).toEqual([]);
  });

  it('réponse libre : un message texte répond à la question en attente', async () => {
    const { hub: h } = hub();
    const answers: string[] = [];
    h.offer('Question', '', [], true, (c) => answers.push(c));
    h.onText('ma réponse');
    expect(answers).toEqual(['ma réponse']);
  });

  it('annulation : le message du téléphone est mis à jour', async () => {
    const { bot, hub: h } = hub();
    const handle = h.offer('Q', '', ['A'], false, () => {})!;
    await flush();
    handle.cancel('Réponse terminée');
    expect(bot.edits[0].text).toContain('Réponse terminée');
  });

  it('messages libres : routés vers la conversation qui a parlé, ou celle à qui on répond', async () => {
    const { bot, hub: h } = hub();
    const got: string[] = [];
    const sink = (n: string): RemoteSink => ({ onPhoneMessage: (t) => got.push(`${n}:${t}`) });
    const a = sink('A'); const b = sink('B');
    h.register(a); h.register(b);
    h.onText('seul'); await flush();
    expect(bot.sent.at(-1)!.text).toMatch(/Aucune conversation/); // deux conversations, aucune active
    await h.say(b, 'fini'); // B parle en dernier
    h.onText('suite');
    await h.say(a, 'autre'); // A parle ; la réponse explicite à B reste pour B
    const idOfB = [...(h as any).owners.entries()].find(([, v]) => v === b)![0];
    h.onText('pour B', idOfB);
    expect(got).toEqual(['B:suite', 'B:pour B']);
    h.unregister(b);
    h.onText('encore'); // B est parti : A est la dernière conversation active
    expect(got.at(-1)).toBe('A:encore');
  });

  it('un seul abonné reçoit les messages même sans avoir parlé', () => {
    const { hub: h } = hub();
    const got: string[] = [];
    h.register({ onPhoneMessage: (t) => got.push(t) });
    h.onText('salut');
    expect(got).toEqual(['salut']);
  });
});

describe('TelegramBot (API simulée)', () => {
  let server: Server;
  afterEach(() => server?.close());

  async function start(updates: object[][]): Promise<{ url: string; calls: Array<{ method: string; body: any }> }> {
    const calls: Array<{ method: string; body: any }> = [];
    server = createServer((req, res) => {
      let data = '';
      req.on('data', (c) => { data += c; });
      req.on('end', () => {
        const method = req.url!.split('/').pop()!;
        const body = data ? JSON.parse(data) : {};
        calls.push({ method, body });
        const result = method === 'getUpdates' ? (updates.shift() ?? []) : { message_id: 7 };
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify({ ok: true, result }));
      });
    });
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
    return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, calls };
  }

  const handler = () => {
    const ev: string[] = [];
    const h: TelegramHandler = { onCallback: (d) => ev.push(`cb:${d}`), onText: (t, r) => ev.push(`text:${t}:${r ?? ''}`), onPaired: (c) => ev.push(`paired:${c}`) };
    return { ev, h };
  };

  it('envoie un message avec des boutons', async () => {
    const { url, calls } = await start([]);
    const bot = new TelegramBot('TOK', 5, handler().h, url);
    expect(await bot.send('bonjour', [['Oui', 'a:0']])).toBe(7);
    expect(calls[0].body).toMatchObject({ chat_id: 5, text: 'bonjour', reply_markup: { inline_keyboard: [[{ text: 'Oui', callback_data: 'a:0' }]] } });
    await bot.edit(7, 'fini');
    expect(calls[1]).toMatchObject({ method: 'editMessageText', body: { message_id: 7, text: 'fini' } });
  });

  it('appairage : le bon code enregistre le chat, les autres sont ignorés', async () => {
    const { url } = await start([]);
    const { ev, h } = handler();
    const bot = new TelegramBot('TOK', undefined, h, url);
    bot.pairingCode = '123456';
    await bot.handle({ update_id: 1, message: { chat: { id: 9 }, text: '/pair 000000' } });
    expect(bot.chatId).toBeUndefined();
    await bot.handle({ update_id: 2, message: { chat: { id: 9 }, text: '/pair 123456' } });
    expect(bot.chatId).toBe(9);
    expect(ev).toEqual(['paired:9']);
    expect(bot.pairingCode).toBeUndefined(); // usage unique
  });

  it('le code expire et est révoqué après 5 essais', async () => {
    const { url } = await start([]);
    let now = 0;
    const bot = new TelegramBot('TOK', undefined, handler().h, url, 25, () => now);
    bot.pairingCode = '111111';
    now = 5 * 60_000 + 1;
    await bot.handle({ update_id: 1, message: { chat: { id: 9 }, text: '/pair 111111' } });
    expect(bot.chatId).toBeUndefined(); // expiré
    now = 0; bot.pairingCode = '222222';
    for (let i = 0; i < 5; i++) await bot.handle({ update_id: i, message: { chat: { id: 9 }, text: '/pair 999999' } });
    await bot.handle({ update_id: 9, message: { chat: { id: 9 }, text: '/pair 222222' } });
    expect(bot.chatId).toBeUndefined(); // révoqué malgré le bon code
  });

  it('n\'écoute que le chat appairé', async () => {
    const { url } = await start([]);
    const { ev, h } = handler();
    const bot = new TelegramBot('TOK', 5, h, url);
    await bot.handle({ update_id: 1, message: { chat: { id: 6 }, text: 'intrus' } });
    await bot.handle({ update_id: 2, callback_query: { id: 'x', data: 'zz:0', message: { chat: { id: 6 } } } });
    await bot.handle({ update_id: 3, message: { chat: { id: 5 }, text: 'ok', reply_to_message: { message_id: 42 } } });
    await bot.handle({ update_id: 4, callback_query: { id: 'y', data: 'ab:1', message: { chat: { id: 5 } } } });
    expect(ev).toEqual(['text:ok:42', 'cb:ab:1']);
  });

  it('long polling : lit les mises à jour puis s\'arrête', async () => {
    const { url, calls } = await start([[{ update_id: 10, message: { chat: { id: 5 }, text: 'salut' } }]]);
    const { ev, h } = handler();
    const bot = new TelegramBot('TOK', 5, h, url, 0);
    bot.start();
    for (let i = 0; i < 100 && !ev.length; i++) await flush();
    bot.stop();
    expect(ev).toEqual(['text:salut:']);
    expect(calls.filter((c) => c.method === 'getUpdates')[1]?.body.offset).toBe(11); // l'acquittement avance l'offset
  });
});
