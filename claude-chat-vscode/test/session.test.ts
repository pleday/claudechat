import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';
import { RemoteBridge } from '../src/core/remote';
import { paths } from '../src/core/sessions';
import { ChatSession, proposedText, type SessionEnv } from '../src/host/chatSession';
import type { HostMsg, UiState } from '../src/protocol';
import { fakeCli, tmp } from './helpers';

/** Faux environnement : enregistre tout ce que la conversation montre ou demande à VS Code. */
function makeEnv(cwd: string, cli: string, over: Partial<SessionEnv> = {}, cfg: Record<string, unknown> = {}) {
  const posts: HostMsg[] = [];
  const notes: string[] = [];
  const state = new Map<string, unknown>();
  const diffs: Array<{ id: string; path: string; original: string; proposed: string }> = [];
  const titles: string[] = [];
  const env: SessionEnv = {
    cwd: () => cwd,
    config: <T>(k: string, f: T) => (k in cfg ? (cfg[k] as T) : f),
    getState: <T>(k: string, f: T) => (state.has(k) ? (state.get(k) as T) : f),
    setState: (k, v) => { state.set(k, v); },
    post: (m) => posts.push(m),
    notify: (_k, m) => notes.push(m),
    confirm: async () => true,
    inputBox: async () => undefined,
    editorContext: () => undefined,
    editorLabel: () => '',
    pickFiles: async () => [],
    showDiff: async (id, path, original, proposed) => { diffs.push({ id, path, original, proposed }); },
    readEdited: async () => undefined,
    closeDiff: () => {},
    applyFile: async (p, t) => { writeFileSync(p, t); },
    refreshFiles: () => {},
    openFile: () => {},
    openChanges: () => {},
    setTitle: (t) => { titles.push(t); },
    onChanges: () => {},
    remote: new RemoteBridge(),
    openSettings: () => {},
    openMcp: () => {},
    projectFiles: async () => ['src/a.ts', 'README.md'],
    showSessions: () => {},
    cliPath: () => cli,
    ...over,
  };
  const ui = (): Partial<UiState> => Object.assign({}, ...posts.filter((p): p is Extract<HostMsg, { t: 'state' }> => p.t === 'state').map((p) => p.patch));
  return { env, posts, notes, diffs, titles, state, ui };
}

async function until(cond: () => boolean, what = 'condition', ms = 10_000): Promise<void> {
  const t0 = Date.now();
  while (!cond()) {
    if (Date.now() - t0 > ms) throw new Error(`délai dépassé : ${what}`);
    await new Promise((r) => setTimeout(r, 15));
  }
}

// la conversation attend les serveurs MCP avant d'envoyer le message : le faux exécutable répond comme la vraie CLI
const PRE = `read -r mcp
echo '{"type":"control_response","response":{"subtype":"success","request_id":"mcp-wait-0","response":{"mcpServers":[]}}}'
read -r l`;
const RESULT = `echo '{"type":"result","is_error":false,"usage":{"input_tokens":1,"output_tokens":2},"total_cost_usd":0.01}'`;
const INIT = `echo '{"type":"system","subtype":"init","session_id":"sid-42","model":"claude-sonnet-5-5"}'`;
const text = (t: string) => `echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"${t}"}}}'`;

beforeEach(() => {
  const home = tmp();
  paths.claudeDir = () => join(home, '.claude');
  paths.dataHome = () => join(home, 'data');
});

describe('ChatSession', () => {
  it('conversation simple : message, réponse en streaming, état final', async () => {
    const d = tmp();
    const cli = fakeCli(d, `${PRE}\n${INIT}\n${text('Bon')}\n${text('jour')}\n${RESULT}`);
    const { env, posts, ui, titles } = makeEnv(d, cli);
    const s = new ChatSession(env);
    await s.onMessage({ t: 'send', text: 'Dis bonjour' });
    await until(() => s.session.entries.length === 2 && !s.isStreaming, 'fin de réponse');
    expect(s.session.id).toBe('sid-42');
    expect(s.session.entries.map((e) => [e.kind, e.text])).toEqual([['user', 'Dis bonjour'], ['text', 'Bonjour']]);
    expect(s.session.title).toBe('Dis bonjour');
    expect(titles).toContain('Dis bonjour');
    expect(ui().streaming).toBe(false);
    expect(ui().usageLine).toContain('↑ 1 ↓ 2');
    // le texte en cours est regroupé : la dernière mise à jour contient tout le texte
    const patches = posts.filter((p) => p.t === 'patch');
    expect(patches.at(-1)).toMatchObject({ patch: { text: 'Bonjour' } });
    s.dispose();
  });

  it('joint le fichier mentionné avec @ et expande les raccourcis', async () => {
    const d = tmp();
    writeFileSync(join(d, 'notes.md'), 'contenu secret');
    const out = join(d, 'stdin.txt');
    const cli = fakeCli(d, `${PRE}\necho "$l" > ${out}\n${INIT}\n${RESULT}`);
    const { env } = makeEnv(d, cli);
    const s = new ChatSession(env);
    await s.onMessage({ t: 'send', text: '/explain @notes.md' });
    await until(() => !s.isStreaming && s.session.entries.length > 0 && readFileSync(out, 'utf8').length > 0, 'envoi');
    const sent = JSON.parse(readFileSync(out, 'utf8'));
    const body: string = sent.message.content.at(-1).text;
    expect(body).toContain('<file path="notes.md">\ncontenu secret\n</file>');
    expect(body).toContain('Explique ce code');
    s.dispose();
  });

  it('permission : l\'interaction est proposée, « Autoriser » répond à la CLI', async () => {
    const d = tmp();
    const out = join(d, 'reply.txt');
    const cli = fakeCli(d, `${PRE}
echo '{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"}}}'
read -r reply
echo "$reply" > ${out}
${RESULT}`);
    const { env, ui } = makeEnv(d, cli);
    const s = new ChatSession(env);
    await s.onMessage({ t: 'send', text: 'go' });
    await until(() => ui().interaction != null, 'interaction');
    const i = ui().interaction!;
    expect(i.title).toMatch(/^Bash — ls/);
    expect(i.options).toEqual(['Autoriser', 'Refuser', 'Tout autoriser (session)']);
    expect(i.canDiff).toBe(false);
    await s.onMessage({ t: 'interact', id: i.id, choice: 'Autoriser' });
    await until(() => !s.isStreaming, 'fin');
    expect(JSON.parse(readFileSync(out, 'utf8')).response.response).toMatchObject({ behavior: 'allow', updatedInput: { command: 'ls' } });
    expect(ui().interaction).toBeNull();
    s.dispose();
  });

  it('« Tout autoriser » : les demandes suivantes sont acceptées sans question', async () => {
    const d = tmp();
    const out = join(d, 'replies.txt');
    const req = (id: string) => `echo '{"type":"control_request","request_id":"${id}","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"x"}}}'`;
    const cli = fakeCli(d, `${PRE}\n${req('a')}\nread -r r1\n${req('b')}\nread -r r2\necho "$r1" >> ${out}; echo "$r2" >> ${out}\n${RESULT}`);
    const { env, ui } = makeEnv(d, cli);
    const s = new ChatSession(env);
    await s.onMessage({ t: 'send', text: 'go' });
    await until(() => ui().interaction != null);
    await s.onMessage({ t: 'interact', id: ui().interaction!.id, choice: 'Tout autoriser (session)' });
    await until(() => !s.isStreaming && (() => { try { return readFileSync(out, 'utf8').trim().split('\n').length === 2; } catch { return false; } })(), 'deux réponses');
    expect(readFileSync(out, 'utf8').trim().split('\n').map((l) => JSON.parse(l).response.response.behavior)).toEqual(['allow', 'allow']);
    s.dispose();
  });

  it('modification de fichier : diff ouvert, version corrigée par l\'utilisateur appliquée à la place', async () => {
    const d = tmp();
    const f = join(d, 'a.txt');
    writeFileSync(f, 'un\ndeux\n');
    const out = join(d, 'reply.txt');
    const cli = fakeCli(d, `${PRE}
echo '{"type":"control_request","request_id":"e1","request":{"subtype":"can_use_tool","tool_name":"Edit","input":{"file_path":"${f}","old_string":"deux","new_string":"DEUX"}}}'
read -r reply
echo "$reply" > ${out}
${RESULT}`);
    const m = makeEnv(d, cli, { readEdited: async () => 'un\nDEUX corrigé par moi\n' });
    const s = new ChatSession(m.env);
    await s.onMessage({ t: 'send', text: 'edit' });
    await until(() => m.diffs.length === 1, 'diff');
    expect(m.diffs[0]).toMatchObject({ path: f, original: 'un\ndeux\n', proposed: 'un\nDEUX\n' });
    expect(m.ui().interaction?.canDiff).toBe(true);
    await s.onMessage({ t: 'interact', id: m.ui().interaction!.id, choice: 'Autoriser' });
    await until(() => !s.isStreaming, 'fin');
    expect(readFileSync(f, 'utf8')).toBe('un\nDEUX corrigé par moi\n');
    const body = JSON.parse(readFileSync(out, 'utf8')).response.response;
    expect(body.behavior).toBe('deny');
    expect(body.message).toContain("L'utilisateur a modifié ta proposition");
    s.dispose();
  });

  it('plan et questions de Claude', async () => {
    const d = tmp();
    const out = join(d, 'replies.txt');
    const cli = fakeCli(d, `${PRE}
echo '{"type":"control_request","request_id":"p1","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"header":"Choix","question":"Quelle langue ?","options":[{"label":"Français"},{"label":"Anglais"}]}]}}}'
read -r r1
echo '{"type":"control_request","request_id":"p2","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","input":{"plan":"1. faire"}}}'
read -r r2
echo "$r1" >> ${out}; echo "$r2" >> ${out}
${RESULT}`);
    const { env, ui } = makeEnv(d, cli);
    const s = new ChatSession(env);
    await s.onMessage({ t: 'send', text: 'go' });
    await until(() => ui().interaction?.title === 'Choix');
    expect(ui().interaction).toMatchObject({ freeText: true, options: ['Français', 'Anglais'], body: 'Quelle langue ?' });
    await s.onMessage({ t: 'interact', id: ui().interaction!.id, choice: 'Une autre langue' }); // réponse libre
    await until(() => ui().interaction?.title === 'Plan proposé par Claude');
    expect(ui().interaction?.body).toBe('1. faire');
    await s.onMessage({ t: 'interact', id: ui().interaction!.id, choice: 'Refuser' });
    await until(() => !s.isStreaming);
    const [r1, r2] = readFileSync(out, 'utf8').trim().split('\n').map((l) => JSON.parse(l).response.response);
    expect(r1.updatedInput.answers).toEqual({ 'Quelle langue ?': 'Une autre langue' });
    expect(r2).toEqual({ behavior: 'deny', message: "L'utilisateur a refusé le plan." });
    s.dispose();
  });

  it('file d\'attente : un message envoyé pendant la réponse part après', async () => {
    const d = tmp();
    const out = join(d, 'msgs.txt');
    const cli = fakeCli(d, `${PRE}\necho "$l" >> ${out}\nsleep 0.6\n${INIT}\n${RESULT}`);
    const { env, ui } = makeEnv(d, cli);
    const s = new ChatSession(env);
    await s.onMessage({ t: 'send', text: 'premier' });
    await s.onMessage({ t: 'send', text: 'deuxième', mode: 'queue' });
    expect(ui().queue).toEqual(['deuxième']);
    await until(() => ui().queue?.length === 0 && s.session.entries.filter((e) => e.kind === 'user').length === 2, 'envoi différé');
    await until(() => !s.isStreaming && readFileSync(out, 'utf8').trim().split('\n').length === 2, 'deux tours');
    s.dispose();
  });

  it('interruption : « Réponse interrompue » et le processus s\'arrête', async () => {
    const d = tmp();
    const cli = fakeCli(d, `${PRE}\n${INIT}\nwhile read -r x; do case "$x" in *interrupt*) exit 0;; esac; done`);
    const { env } = makeEnv(d, cli);
    const s = new ChatSession(env);
    await s.onMessage({ t: 'send', text: 'long' });
    await until(() => s.session.id === 'sid-42');
    s.stop();
    await until(() => !s.isStreaming);
    expect(s.session.entries.at(-1)).toMatchObject({ kind: 'notice', text: 'Réponse interrompue.' });
    s.dispose();
  });

  it('erreur de la CLI : affichée dans la conversation', async () => {
    const d = tmp();
    const cli = fakeCli(d, `${PRE}\necho '{"type":"result","is_error":true,"result":"boum"}'`);
    const { env } = makeEnv(d, cli);
    const s = new ChatSession(env);
    await s.onMessage({ t: 'send', text: 'x' });
    await until(() => !s.isStreaming && s.session.entries.length === 2);
    expect(s.session.entries[1]).toMatchObject({ kind: 'error', text: 'boum' });
    s.dispose();
  });

  it('fichiers modifiés par Claude : outil d\'édition et commande Bash', async () => {
    const d = tmp();
    const a = join(d, 'a.txt');
    writeFileSync(a, 'avant\n');
    const tool = (id: string, name: string, input: string) => `echo '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"${id}","name":"${name}","input":${input}}]}}'`;
    const res = (id: string) => `echo '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"${id}","content":"ok"}]}}'`;
    const cli = fakeCli(d, `${PRE}
${INIT}
sleep 0.5
${tool('t1', 'Edit', `{"file_path":"${a}"}`)}
sleep 0.3
echo "après plus long" > ${a}
${res('t1')}
${tool('t2', 'Bash', '{"command":"echo"}')}
sleep 0.3
echo "créé par bash" > ${join(d, 'b.txt')}
${res('t2')}
${RESULT}`);
    const m = makeEnv(d, cli);
    const s = new ChatSession(m.env);
    await s.onMessage({ t: 'send', text: 'modifie' });
    await until(() => !s.isStreaming);
    await until(() => (m.ui().changes?.count ?? 0) === 2, 'deux fichiers modifiés');
    expect(s.changedFiles.map((c) => `${c.status}:${c.rel}`).sort()).toEqual(['modified:a.txt', 'new:b.txt']);
    expect(m.ui().changes).toMatchObject({ count: 2 });
    await s.onMessage({ t: 'forgetChanges' });
    expect(m.ui().changes).toBeNull();
    s.dispose();
  });

  it('mode de permission, modèle et effort : appliqués et mémorisés', async () => {
    const d = tmp();
    const cli = fakeCli(d, 'sleep 5');
    const m = makeEnv(d, cli);
    const s = new ChatSession(m.env);
    await s.onMessage({ t: 'setMode', id: 'plan' });
    await s.onMessage({ t: 'setModel', id: 'claude-haiku-5-5' });
    await s.onMessage({ t: 'setEffort', id: 'high' });
    expect(m.ui()).toMatchObject({ mode: 'plan', model: 'claude-haiku-5-5' });
    expect(m.state.get('permissionMode')).toBe('plan');
    expect(m.state.get('model')).toBe('claude-haiku-5-5');
    s.dispose();
  });

  it('mentions : recherche floue dans les fichiers du projet', async () => {
    const d = tmp();
    const m = makeEnv(d, fakeCli(d, 'sleep 5'));
    const s = new ChatSession(m.env);
    await s.onMessage({ t: 'mention', seq: 3, q: 'read' });
    expect(m.posts.find((p) => p.t === 'mention')).toEqual({ t: 'mention', seq: 3, results: ['README.md'] });
    s.dispose();
  });

  it('nouvelle conversation : demande confirmation pendant une réponse', async () => {
    const d = tmp();
    const cli = fakeCli(d, `${PRE}\n${INIT}\nsleep 5`);
    const asked: string[] = [];
    const m = makeEnv(d, cli, { confirm: async (msg) => { asked.push(msg); return false; } });
    const s = new ChatSession(m.env);
    await s.onMessage({ t: 'send', text: 'x' });
    await until(() => s.session.id === 'sid-42');
    await s.newSession();
    expect(asked[0]).toContain('Claude est en train de répondre');
    expect(s.session.entries).toHaveLength(1); // refusé : rien n'est perdu
    s.dispose();
  });
});

describe('proposedText (diff d\'approbation)', () => {
  const req = (tool: string, input: object) => ({ requestId: 'r', tool, input, description: '' });
  it('Edit, MultiEdit, Write', () => {
    expect(proposedText(req('Edit', { old_string: 'b', new_string: 'X' }), 'abcb')).toBe('aXcb');
    expect(proposedText(req('Edit', { old_string: 'b', new_string: 'X', replace_all: true }), 'abcb')).toBe('aXcX');
    expect(proposedText(req('Edit', { old_string: 'b', new_string: '$&' }), 'abc')).toBe('a$&c'); // pas d'interprétation de $
    expect(proposedText(req('MultiEdit', { edits: [{ old_string: 'a', new_string: '1' }, { old_string: 'b', new_string: '2' }] }), 'ab')).toBe('12');
    expect(proposedText(req('Write', { content: 'neuf' }), 'ancien')).toBe('neuf');
    expect(proposedText(req('Bash', {}), 'x')).toBeUndefined();
  });
});
