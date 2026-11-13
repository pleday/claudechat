import { diffLines } from 'diff';
import * as fsSync from 'node:fs';
import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import type { Json } from './cli';
import { GENERATED_DIRS, LIMITS } from './constants';

export type ChangeStatus = 'modified' | 'new' | 'deleted';

/** Un fichier modifié par Claude : `before` est son contenu avant la première modification (null : le fichier n'existait pas). */
export interface ChangedFile {
  path: string;
  rel: string;
  status: ChangeStatus;
  added: number;
  removed: number;
  before: string | null;
}

/** Contenu d'un fichier texte ; null s'il n'existe pas ; lève une erreur s'il est trop gros ou binaire. */
export function readTextOrNull(p: string, maxBytes = 2 * 1024 * 1024): string | null {
  let st: fsSync.Stats;
  try { st = fsSync.statSync(p); } catch { return null; }
  if (!st.isFile()) return null;
  if (st.size > maxBytes) throw new Error('fichier trop volumineux');
  return fsSync.readFileSync(p, 'utf8');
}

export function lineCount(text: string): number {
  return text === '' ? 0 : text.split('\n').length - (text.endsWith('\n') ? 1 : 0);
}

/** (lignes ajoutées, lignes retirées) de `old` vers `next`. */
export function lineStats(old: string, next: string): [number, number] {
  if (old === '') return [lineCount(next), 0];
  if (next === '') return [0, lineCount(old)];
  let a = 0; let r = 0;
  for (const part of diffLines(old, next)) {
    if (part.added) a += part.count ?? 0;
    else if (part.removed) r += part.count ?? 0;
  }
  return [a, r];
}

/**
 * Fichiers modifiés par Claude pendant la conversation. Le contenu d'un fichier est mémorisé juste avant le premier
 * outil qui le modifie (Edit, Write, MultiEdit, NotebookEdit) : la comparaison montre ainsi tout ce que Claude a changé
 * depuis le début de la conversation, et pas seulement la dernière modification. L'état actuel est relu sur le disque.
 *
 * Les changements faits par des commandes (Bash, sous-agents, outils MCP) sont retrouvés après coup grâce à la photo du
 * projet (FsBaseline, afterCommand). Dans les deux cas, seul ce que Claude a fait est suivi : ni git, ni les modifications
 * que l'utilisateur fait à la main n'interviennent (voir startTurn).
 */
export class ChangeTracker {
  static readonly TOOLS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit']);
  private before = new Map<string, string | null>();
  // Contenu de chaque fichier suivi à la fin de la dernière réponse : si, au début de la suivante, il a changé, c'est que
  // l'utilisateur l'a modifié entre-temps ; le suivi du fichier repart alors de son état actuel.
  private settled = new Map<string, string | null>();

  constructor(
    private cwd: () => string,
    private read: (p: string) => string | null = readTextOrNull,
    private stats: (a: string, b: string) => [number, number] = lineStats,
  ) {}

  private abs(raw: string): string {
    return path.normalize(path.isAbsolute(raw) ? raw : path.join(this.cwd(), raw));
  }

  /** À appeler dès que Claude annonce un outil, avant qu'il s'exécute. Sans effet pour les autres outils et les fichiers déjà suivis. */
  beforeEdit(tool: string, input: Json): void {
    if (!ChangeTracker.TOOLS.has(tool)) return;
    const raw = input.file_path ?? input.notebook_path ?? input.path;
    if (typeof raw !== 'string') return;
    const p = this.abs(raw);
    if (this.before.has(p)) return;
    let old: string | null;
    try { old = this.read(p); } catch { return; } // fichier illisible (binaire, trop gros) : non suivi
    this.before.set(p, old);
  }

  /**
   * À appeler après une commande (Bash, sous-agent, outil MCP) exécutée entre `startMs` et `endMs` : retient les
   * fichiers qu'elle a créés, modifiés ou supprimés, avec leur état d'avant. Un fichier déjà suivi garde son état initial.
   */
  async afterCommand(baseline: FsBaseline, startMs: number, endMs: number): Promise<void> {
    for (const o of await baseline.scan(startMs, endMs)) if (!this.before.has(o.path)) this.before.set(o.path, o.before);
  }

  /** Fin d'une réponse : mémorise l'état des fichiers suivis. */
  endTurn(): void {
    this.settled.clear();
    for (const p of this.before.keys()) {
      try { this.settled.set(p, this.read(p)); } catch { /* illisible : ignoré */ }
    }
  }

  /** Début d'une réponse : oublie les fichiers que l'utilisateur a modifiés depuis la fin de la précédente. */
  startTurn(): void {
    const check = new Map(this.settled);
    this.settled.clear();
    for (const [p, content] of check) {
      let now: string | null;
      try { now = this.read(p); } catch { now = null; }
      if (now !== content) this.before.delete(p);
    }
  }

  get isEmpty(): boolean { return this.before.size === 0; }

  /** Oublie tout : les prochaines modifications repartent de l'état du disque à ce moment. */
  clear(): void { this.before.clear(); this.settled.clear(); }

  /** Fichiers réellement différents de leur état initial (une modification refusée ou annulée n'apparaît pas). */
  changes(): ChangedFile[] {
    const base = this.cwd();
    const out: ChangedFile[] = [];
    for (const [p, old] of this.before) {
      let now: string | null;
      try { now = this.read(p); } catch { continue; }
      const rel = p.startsWith(base + path.sep) ? p.slice(base.length + 1) : p;
      if (old === null && now === null) continue;
      if (old === null) out.push({ path: p, rel, status: 'new', added: lineCount(now!), removed: 0, before: null });
      else if (now === null) out.push({ path: p, rel, status: 'deleted', added: 0, removed: lineCount(old), before: old });
      else if (old !== now) { const [a, r] = this.stats(old, now); out.push({ path: p, rel, status: 'modified', added: a, removed: r, before: old }); }
    }
    return out;
  }
}

interface Stat { size: number; mtime: number }
interface SnapEntry { stat: Stat; content: string | null }
export interface Observed { path: string; kind: 'modified' | 'new' | 'deleted'; before: string | null }

export { GENERATED_DIRS };

/**
 * Photo du projet (taille, date et contenu des petits fichiers texte), prise avant que Claude agisse. Une commande (Bash,
 * sous-agent, outil MCP) peut toucher n'importe quel fichier sans le dire : la photo permet, après coup, de retrouver ce
 * qu'elle a changé et l'état d'avant. Indépendant de git.
 *
 * Attribution à Claude : un fichier existant n'est retenu que si sa date de modification tombe dans la fenêtre d'exécution de
 * la commande. Créations, suppressions et renommages se repèrent par comparaison avec la photo.
 */
export class FsBaseline {
  private snapshot = new Map<string, SnapEntry>();
  private truncated = false;

  constructor(
    private root: () => string,
    private skipDir: (name: string) => boolean = () => false,
    private skipFile: (name: string) => boolean = () => false,
    private maxFileBytes = LIMITS.baselineMaxFileBytes,
    private maxTotalBytes = LIMITS.baselineMaxTotalBytes,
    private maxEntries = LIMITS.baselineMaxEntries,
  ) {}

  get fileCount(): number { return this.snapshot.size; }

  private async walk(): Promise<{ files: Map<string, Stat>; truncated: boolean }> {
    const files = new Map<string, Stat>();
    let cut = false;
    const base = this.root();
    const visit = async (dir: string): Promise<void> => {
      let items: fsSync.Dirent[];
      try { items = await fs.readdir(dir, { withFileTypes: true }); } catch { return; }
      for (const it of items) {
        if (cut) return;
        const full = path.join(dir, it.name);
        if (it.isDirectory()) { if (!this.skipDir(it.name)) await visit(full); continue; }
        if (!it.isFile() || this.skipFile(it.name)) continue;
        if (files.size >= this.maxEntries) { cut = true; return; }
        try { const st = await fs.stat(full); files.set(full, { size: st.size, mtime: Math.floor(st.mtimeMs) }); } catch { /* disparu */ }
      }
    };
    await visit(base);
    return { files, truncated: cut };
  }

  /** Contenu d'un petit fichier texte (UTF-8 strict, sans octet nul) ; null sinon. */
  private async readText(p: string, size: number): Promise<string | null> {
    if (size > this.maxFileBytes) return null;
    try {
      const buf = await fs.readFile(p);
      if (buf.includes(0)) return null;
      return new TextDecoder('utf-8', { fatal: true }).decode(buf);
    } catch { return null; }
  }

  /**
   * Prend la photo. Les fichiers dont la taille et la date n'ont pas changé depuis la précédente gardent leur contenu :
   * seuls les fichiers modifiés sont relus.
   */
  async capture(): Promise<void> {
    const w = await this.walk();
    const old = this.snapshot;
    let used = 0;
    const out = new Map<string, SnapEntry>();
    for (const [p, st] of w.files) {
      const prev = old.get(p);
      let content = prev && prev.stat.size === st.size && prev.stat.mtime === st.mtime ? prev.content : await this.readText(p, st.size);
      if (content !== null) { if (used + content.length > this.maxTotalBytes) content = null; else used += content.length; }
      out.set(p, { stat: st, content });
    }
    this.snapshot = out;
    this.truncated = w.truncated;
  }

  /**
   * Ce qu'une commande exécutée entre `startMs` et `endMs` a changé depuis la photo. Vide si la photo manque ou
   * est incomplète (projet trop gros) : mieux vaut ne rien afficher que de prendre tout le projet pour des créations.
   */
  async scan(startMs: number, endMs: number, slackMs = 1500): Promise<Observed[]> {
    const base = this.snapshot;
    if (base.size === 0 || this.truncated) return [];
    const now = await this.walk();
    const out: Observed[] = [];
    const from = startMs - slackMs; const to = endMs + slackMs;
    for (const [p, st] of now.files) {
      const e = base.get(p);
      if (!e) out.push({ path: p, kind: 'new', before: null });
      else if ((e.stat.size !== st.size || e.stat.mtime !== st.mtime) && st.mtime >= from && st.mtime <= to && e.content !== null) {
        out.push({ path: p, kind: 'modified', before: e.content });
      }
    }
    if (!now.truncated) for (const [p, e] of base) if (!now.files.has(p) && e.content !== null) out.push({ path: p, kind: 'deleted', before: e.content });
    return out;
  }
}
