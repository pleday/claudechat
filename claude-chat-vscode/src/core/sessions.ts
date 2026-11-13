import { promises as fs } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import type { Json } from './cli';

export type Kind = 'user' | 'text' | 'thinking' | 'tool' | 'notice' | 'error';

/** Un élément du transcript. */
export interface Entry {
  kind: Kind;
  text: string;
  toolId?: string;
  toolName?: string;
  input?: Json;
  result?: string;
  isError?: boolean;
  /** Pièces jointes du message (noms séparés par des virgules). */
  attached?: string;
  /** Mention affichée à côté de « Vous » (ex. précision envoyée en cours de réponse). */
  note?: string;
  /** Modèle ayant produit ce contenu (réponses de Claude). */
  model?: string;
}

/** Conversation en cours. L'identifiant est celui de la session de la CLI. */
export interface Session {
  id?: string;
  title: string;
  entries: Entry[];
}

export const DEFAULT_TITLE = 'Nouvelle conversation';

export interface SessionInfo { id: string; title: string; updated: number; archived: boolean; file: string }

/** Contenu textuel d'un résultat d'outil. */
export function resultText(c: unknown): string {
  if (c == null) return '';
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.map((b) => (typeof b?.text === 'string' ? b.text : '')).join('\n');
  return JSON.stringify(c);
}

/** Dossiers de données, surchargeables pour les tests. */
export const paths = {
  /** Dossier de données du plugin : ~/.claude-code-gui ou CCG_HOME (le même que le plugin JetBrains : titres et archives partagés). */
  dataHome: (): string => process.env.CCG_HOME?.trim() || path.join(os.homedir(), '.claude-code-gui'),
  /** Dossier de données de la CLI. */
  claudeDir: (): string => path.join(os.homedir(), '.claude'),
};

export function projectDir(cwd: string): string {
  return path.join(paths.claudeDir(), 'projects', cwd.replace(/[^A-Za-z0-9]/g, '-'));
}

interface Meta { titles: Record<string, string>; archived: string[] }

const metaFile = () => path.join(paths.dataHome(), 'session-meta.json');

/** Lecture tolérante : un champ absent ou null (fichier ancien ou tronqué) ne doit pas faire planter. */
async function readMeta(): Promise<Meta> {
  try {
    const o = JSON.parse(await fs.readFile(metaFile(), 'utf8'));
    const titles: Record<string, string> = {};
    if (o?.titles && typeof o.titles === 'object') for (const [k, v] of Object.entries(o.titles)) if (typeof v === 'string') titles[k] = v;
    const archived = Array.isArray(o?.archived) ? o.archived.filter((a: unknown): a is string => typeof a === 'string') : [];
    return { titles, archived };
  } catch { return { titles: {}, archived: [] }; }
}

/** Écriture atomique (fichier temporaire puis renommage) : un arrêt ou un second IDE ne laisse jamais un JSON tronqué. */
async function saveMeta(m: Meta): Promise<void> {
  await fs.mkdir(paths.dataHome(), { recursive: true });
  const tmp = metaFile() + '.tmp';
  await fs.writeFile(tmp, JSON.stringify(m));
  await fs.rename(tmp, metaFile());
}

// les écritures de session-meta.json sont sérialisées (lecture-modification-écriture)
let lock: Promise<unknown> = Promise.resolve();
const locked = <T>(fn: () => Promise<T>): Promise<T> => { const r = lock.then(fn, fn); lock = r.catch(() => {}); return r; };

/** Titre écrit par la CLI dans la session : « custom-title » (renommage) avant « ai-title » (titre généré). */
class TitleScan {
  custom?: string;
  ai?: string;
  feed(line: string): void {
    const isCustom = line.includes('"custom-title"');
    if (!isCustom && !line.includes('"ai-title"')) return;
    let o: Json;
    try { o = JSON.parse(line); } catch { return; }
    if (isCustom && o.type === 'custom-title' && typeof o.customTitle === 'string' && o.customTitle.trim()) this.custom = o.customTitle;
    else if (o.type === 'ai-title' && typeof o.aiTitle === 'string' && o.aiTitle.trim()) this.ai = o.aiTitle;
  }
  get title(): string | undefined { return this.custom ?? this.ai; }
}

/** Texte d'un message utilisateur affichable (ni méta, ni balise interne) ; undefined sinon. */
export function userText(o: Json): string | undefined {
  if (o.isMeta === true) return undefined;
  const c = o.message?.content;
  let t: string;
  if (typeof c === 'string') t = c;
  else if (Array.isArray(c)) {
    t = c.filter((b) => b?.type === 'text' && typeof b.text === 'string').map((b) => b.text).join('\n');
    if (!t) return undefined;
  } else return undefined;
  return t.startsWith('<') ? undefined : t;
}

async function scanTitle(file: string): Promise<string> {
  const scan = new TitleScan();
  let first: string | undefined;
  try {
    for (const l of (await fs.readFile(file, 'utf8')).split('\n')) {
      scan.feed(l);
      if (first === undefined && l.startsWith('{') && l.includes('"type":"user"')) {
        try { first = userText(JSON.parse(l))?.split('\n').find((x) => x.trim())?.slice(0, 60); } catch { /* ligne illisible */ }
      }
    }
  } catch { /* fichier illisible */ }
  return scan.title ?? first ?? 'Conversation';
}

/** Titres déjà calculés, valables tant que la date de modification du fichier ne change pas. */
const titleCache = new Map<string, { mtime: number; title: string }>();

async function cachedTitle(file: string, mtime: number): Promise<string> {
  const hit = titleCache.get(file);
  if (hit?.mtime === mtime) return hit.title;
  if (titleCache.size > 500) titleCache.clear();
  const title = await scanTitle(file);
  titleCache.set(file, { mtime, title });
  return title;
}

/** Liste paginée : les plus récentes d'abord. */
export async function listSessions(cwd: string, offset = 0, limit = 30, includeArchived = false): Promise<SessionInfo[]> {
  const dir = projectDir(cwd);
  let names: string[];
  try { names = (await fs.readdir(dir)).filter((n) => n.endsWith('.jsonl')); } catch { return []; }
  const m = await readMeta();
  const files = await Promise.all(names.map(async (n) => {
    const file = path.join(dir, n);
    const mtime = await fs.stat(file).then((s) => s.mtimeMs, () => 0);
    return { file, mtime, id: n.slice(0, -'.jsonl'.length) };
  }));
  files.sort((a, b) => b.mtime - a.mtime);
  const archived = new Set(m.archived);
  const page = files.filter((f) => includeArchived || !archived.has(f.id)).slice(offset, offset + limit);
  return Promise.all(page.map(async (f) => ({
    id: f.id, title: m.titles[f.id] ?? (await cachedTitle(f.file, f.mtime)), updated: f.mtime, archived: archived.has(f.id), file: f.file,
  })));
}

/**
 * Titre actuel d'une session ouverte, lu dans la fin de son fichier seulement (la CLI réécrit son titre au fil de la
 * conversation) : bon marché même pour une longue session. undefined si la fin du fichier n'en contient pas.
 */
export async function liveTitle(cwd: string, id: string, tailBytes = 256 * 1024): Promise<string | undefined> {
  try {
    const fh = await fs.open(path.join(projectDir(cwd), `${id}.jsonl`), 'r');
    try {
      const { size } = await fh.stat();
      const start = Math.max(0, size - tailBytes);
      const buf = Buffer.alloc(size - start);
      await fh.read(buf, 0, buf.length, start);
      const scan = new TitleScan();
      const lines = buf.toString('utf8').split('\n');
      // si on a commencé au milieu du fichier, la première ligne est tronquée
      (start > 0 ? lines.slice(1) : lines).forEach((l) => scan.feed(l));
      return scan.title;
    } finally { await fh.close(); }
  } catch { return undefined; }
}

/** Reconstitue le transcript d'une session de la CLI (reprise). */
export async function loadSession(info: SessionInfo): Promise<Session> {
  const s: Session = { id: info.id, title: info.title, entries: [] };
  const tools = new Map<string, Entry>();
  for (const l of (await fs.readFile(info.file, 'utf8')).split('\n')) {
    if (!l.startsWith('{')) continue;
    let o: Json;
    try { o = JSON.parse(l); } catch { continue; }
    const model = typeof o.message?.model === 'string' ? o.message.model : undefined;
    if (o.type === 'user') {
      const t = userText(o);
      if (t !== undefined) s.entries.push({ kind: 'user', text: t });
      const c = o.message?.content;
      if (Array.isArray(c)) for (const b of c) {
        if (b?.type === 'tool_result') {
          const e = tools.get(b.tool_use_id);
          if (e) { e.result = resultText(b.content); e.isError = b.is_error === true; }
        }
      }
    } else if (o.type === 'assistant' && Array.isArray(o.message?.content)) {
      for (const b of o.message.content) {
        if (b.type === 'text' && typeof b.text === 'string' && b.text.trim()) s.entries.push({ kind: 'text', text: b.text, model });
        else if (b.type === 'thinking' && typeof b.thinking === 'string' && b.thinking.trim()) s.entries.push({ kind: 'thinking', text: b.thinking, model });
        else if (b.type === 'tool_use') {
          const e: Entry = { kind: 'tool', text: '', toolId: b.id, toolName: b.name, input: b.input, model };
          tools.set(b.id, e);
          s.entries.push(e);
        }
      }
    }
  }
  return s;
}

export const renameSession = (id: string, title: string) =>
  locked(async () => { const m = await readMeta(); m.titles[id] = title; await saveMeta(m); });

export const setArchived = (id: string, archived: boolean) =>
  locked(async () => {
    const m = await readMeta();
    const set = new Set(m.archived);
    if (archived) set.add(id); else set.delete(id);
    m.archived = [...set];
    await saveMeta(m);
  });

export const deleteSession = (info: SessionInfo) =>
  locked(async () => {
    await fs.rm(info.file, { force: true });
    titleCache.delete(info.file);
    const m = await readMeta();
    delete m.titles[info.id];
    m.archived = m.archived.filter((a) => a !== info.id);
    await saveMeta(m);
  });
