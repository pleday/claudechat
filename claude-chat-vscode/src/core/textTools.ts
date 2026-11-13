import type { Json, QuotaWindow, RateLimits } from './cli';

/** Recherche floue de fichiers pour les mentions `@`. */
export const Fuzzy = {
  /** Score d'un chemin pour une requête ; undefined si la requête n'est pas une sous-séquence. Plus haut = meilleur. */
  score(query: string, p0: string): number | undefined {
    if (!query) return 0;
    const q = query.toLowerCase();
    const p = p0.toLowerCase();
    const name = p.slice(p.lastIndexOf('/') + 1);
    let score = 0;
    if (name === q) score += 1000;
    else if (name.startsWith(q)) score += 500;
    else if (name.includes(q)) score += 300;
    else if (p.includes(q)) score += 150;
    let qi = 0;
    let last = -2;
    for (let i = 0; i < p.length; i++) {
      if (qi < q.length && p[i] === q[qi]) {
        score += i === last + 1 ? 5 : 1;
        if (i === 0 || '/_-. '.includes(p[i - 1])) score += 3;
        last = i; qi++;
      }
    }
    if (qi < q.length) return undefined;
    return score - Math.floor(p0.length / 10);
  },
  search(query: string, paths: Iterable<string>, limit = 30): string[] {
    const scored: Array<[string, number]> = [];
    for (const p of paths) { const s = Fuzzy.score(query, p); if (s !== undefined) scored.push([p, s]); }
    return scored.sort((a, b) => b[1] - a[1]).slice(0, limit).map((x) => x[0]);
  },
};

/** Mentions `@chemin` dans un message. */
export const Mentions = {
  /** Chemins mentionnés, dans l'ordre et sans doublon. */
  find(text: string): string[] {
    const out = new Set<string>();
    for (const m of text.matchAll(/(?<![\w@])@([\w./\\-]+)/g)) out.add(m[1]);
    return [...out];
  },
  /** Jeton `@xxx` en cours de frappe juste avant le curseur : (début, requête) ou undefined. */
  activeToken(text: string, caret: number): { start: number; query: string } | undefined {
    const before = text.slice(0, Math.min(Math.max(caret, 0), text.length));
    const m = /(?<![\w@])@([\w./\\-]*)$/.exec(before);
    return m ? { start: m.index, query: m[1] } : undefined;
  },
};

/** Commande `/xxx` en cours de frappe, uniquement en tout début de saisie (seul endroit où elle est interprétée). */
export const SlashToken = {
  activeQuery(text: string, caret: number): string | undefined {
    const before = text.slice(0, Math.min(Math.max(caret, 0), text.length));
    return /^\/([\w:.-]*)$/.exec(before)?.[1];
  },
  filter: (query: string, commands: Iterable<string>) =>
    [...commands].filter((c) => c.toLowerCase().startsWith(query.toLowerCase())).sort(),
};

const s = (i: Json, k: string): string | undefined => (typeof i[k] === 'string' ? i[k] : undefined);

/** Résumé lisible d'un appel d'outil pour les cartes et l'approbation. */
export function toolSummary(name: string, i: Json): string {
  switch (name) {
    case 'Bash': return s(i, 'command') ?? '';
    case 'Read': case 'Write': case 'Edit': case 'MultiEdit': case 'NotebookEdit': return s(i, 'file_path') ?? s(i, 'notebook_path') ?? '';
    case 'Grep': case 'Glob': return [s(i, 'pattern'), s(i, 'path')].filter(Boolean).join(' in ');
    case 'WebFetch': return s(i, 'url') ?? '';
    case 'WebSearch': return s(i, 'query') ?? '';
    case 'Task': case 'Agent': return s(i, 'description') ?? '';
    default: return JSON.stringify(i);
  }
}

/** Description détaillée d'une action à approuver (IDE et téléphone). */
export function describeTool(name: string, i: Json): string {
  switch (name) {
    case 'Bash': return s(i, 'command') ?? '';
    case 'Write': return `Fichier : ${s(i, 'file_path')}\n${(s(i, 'content') ?? '').slice(0, 1500)}`;
    case 'Edit': return `Fichier : ${s(i, 'file_path')}\n- ${(s(i, 'old_string') ?? '').slice(0, 600)}\n+ ${(s(i, 'new_string') ?? '').slice(0, 600)}`;
    case 'MultiEdit': return `Fichier : ${s(i, 'file_path')}\n${Array.isArray(i.edits) ? i.edits.length : 0} modification(s)`;
    default: return toolSummary(name, i);
  }
}

/** Chemin du fichier visé par un outil d'édition. */
export function filePathOf(i: Json): string | undefined {
  const v = i.file_path ?? i.notebook_path ?? i.path;
  return typeof v === 'string' ? v : undefined;
}

export const EDIT_TOOLS = new Set(['Edit', 'Write', 'MultiEdit']);
export const isCommandTool = (name: string) => name === 'Bash' || name === 'Task' || name === 'Agent' || name.startsWith('mcp__');

export const PERMISSION_MODES: ReadonlyArray<readonly [string, string, string]> = [
  ['manual', 'Demander', 'Claude demande ton accord avant chaque action sensible'],
  ['acceptEdits', 'Modifs auto', 'Les modifications de fichiers sont acceptées sans demander ; le reste est demandé'],
  ['plan', 'Plan', "Claude propose un plan à valider avant d'agir"],
  ['auto', 'Auto', 'Mode automatique de la CLI'],
  ['dontAsk', 'Sans demande', 'Tout ce qui demanderait une autorisation est refusé automatiquement'],
  ['bypassPermissions', 'Tout autoriser', 'Aucune demande : Claude agit librement (à réserver aux environnements sûrs)'],
];
export const EFFORTS = ['auto', 'low', 'medium', 'high', 'xhigh', 'max'];

/** Prompts des raccourcis `/explain`, `/fix`… (menu contextuel de l'éditeur et saisie). */
export const SLASH_PROMPTS: Record<string, string> = {
  explain: 'Explique ce code de façon claire : rôle, logique, points délicats.',
  fix: 'Trouve et corrige les bugs ou erreurs de ce code. Explique brièvement chaque correction.',
  tests: 'Écris des tests unitaires pertinents pour ce code (cas nominaux, limites, erreurs), avec le framework de test du projet.',
  doc: 'Ajoute une documentation (commentaires de doc) à ce code sans changer son comportement.',
  review: 'Fais une revue de code : bugs potentiels, sécurité, lisibilité, performances. Classe les remarques par gravité.',
};

/** `/explain détail` devient le prompt complet suivi du détail ; texte inchangé pour toute autre commande. */
export function expandSlash(t: string): string {
  const m = /^\/(\w+)[ \t]*([\s\S]*)$/.exec(t);
  const base = m && SLASH_PROMPTS[m[1].toLowerCase()];
  if (!m || !base) return t;
  const rest = m[2].trim();
  return rest ? `${base}\n\n${rest}` : base;
}

/** Texte et état d'une jauge de quota. */
export const Quota = {
  percent: (w: QuotaWindow | undefined, nowSec: number): number | undefined =>
    w ? (w.resetsAt > 0 && w.resetsAt < nowSec ? 0 : Math.min(100, Math.max(0, Math.trunc(w.utilization * 100)))) : undefined,
  /** Part restante (100 − utilisée) ; undefined si la fenêtre n'est pas connue. */
  remainingPercent(w: QuotaWindow | undefined, nowSec: number): number | undefined {
    const p = Quota.percent(w, nowSec);
    return p === undefined ? undefined : 100 - p;
  },
  /** Durée jusqu'à la réinitialisation, ex. « 2 h 10 » ou « 3 j 4 h ». */
  remaining(resetsAt: number, nowSec: number): string {
    const sec = Math.max(0, resetsAt - nowSec);
    const d = Math.floor(sec / 86400); const h = Math.floor((sec % 86400) / 3600); const m = Math.floor((sec % 3600) / 60);
    if (d > 0) return `${d} j ${h} h`;
    if (h > 0) return `${h} h ${String(m).padStart(2, '0')}`;
    return `${m} min`;
  },
};

/** Détecte un quota rempli et dit quand reprendre (reprise automatique). */
const DEFAULT_QUOTA_DELAY_SEC = 120;
export const QuotaGuard = {
  looksLikeQuota: /(usage limit|limit reached|rate.?limit|quota|limit will reset|resets? at)/i,
  /** Délai par défaut après le début de la session suivante. */
  DEFAULT_DELAY_SEC: DEFAULT_QUOTA_DELAY_SEC,
  FALLBACK_SEC: 1800,
  /** Instant (secondes epoch) où relancer ; undefined si l'échec n'est pas lié à un quota. */
  blockedUntil(l: RateLimits | undefined, message: string | undefined, nowSec: number, delaySec = DEFAULT_QUOTA_DELAY_SEC): number | undefined {
    const rejected = l?.status === 'rejected';
    if (!rejected && !(message && QuotaGuard.looksLikeQuota.test(message))) return undefined;
    const c: number[] = [];
    if (rejected) {
      if (l?.resetsAt) c.push(l.resetsAt);
      if (l?.limitType === 'five_hour' && l.fiveHour) c.push(l.fiveHour.resetsAt);
      if (l?.limitType === 'seven_day' && l.sevenDay) c.push(l.sevenDay.resetsAt);
    }
    if (!c.length) for (const w of [l?.fiveHour, l?.sevenDay]) if (w && w.utilization >= 0.99) c.push(w.resetsAt);
    if (!c.length && l?.fiveHour) c.push(l.fiveHour.resetsAt);
    const live = c.filter((x) => x > nowSec);
    const target = live.length ? Math.max(...live) : nowSec + QuotaGuard.FALLBACK_SEC - delaySec;
    return target + delaySec;
  },
};

/** Découpe un long texte en morceaux d'au plus `max` caractères, de préférence aux sauts de ligne. */
export function chunkText(text: string, max = 3800): string[] {
  const out: string[] = [];
  let rest = text.trim();
  while (rest.length > max) {
    let cut = rest.lastIndexOf('\n', max);
    if (cut <= max / 2) cut = rest.lastIndexOf(' ', max);
    if (cut <= max / 2) cut = max;
    out.push(rest.slice(0, cut).trimEnd());
    rest = rest.slice(cut).trimStart();
  }
  if (rest) out.push(rest);
  return out;
}

/** Aperçu de la file d'attente : dernier message empilé (une ligne) et nombre des autres. */
export function queuePreview(queue: string[], max = 80): string {
  const last = queue.at(-1)?.split('\n').find((l) => l.trim())?.trim();
  if (!last) return '';
  const short = last.length > max ? last.slice(0, max - 1) + '…' : last;
  return queue.length > 1 ? `${short}  (+${queue.length - 1} autre${queue.length > 2 ? 's' : ''})` : short;
}

/** Déplace l'élément `from` à la position `to` (bornée) ; renvoie la nouvelle position. */
export function moveItem<T>(list: T[], from: number, to: number): number {
  if (from < 0 || from >= list.length) return from;
  const dest = Math.min(Math.max(to, 0), list.length - 1);
  list.splice(dest, 0, list.splice(from, 1)[0]);
  return dest;
}

/** Durée lisible : « 42 s », « 1 min 23 s », « 2 min », « 1 h 05 min », « 2 h ». */
export function formatDuration(ms: number): string {
  const total = Math.floor((Math.max(ms, 0) + 500) / 1000); // arrondi à la seconde la plus proche
  if (total < 1) return "moins d'une seconde";
  const h = Math.floor(total / 3600); const m = Math.floor((total % 3600) / 60); const sec = total % 60;
  if (h > 0) return m === 0 ? `${h} h` : `${h} h ${String(m).padStart(2, '0')} min`;
  if (m > 0) return sec === 0 ? `${m} min` : `${m} min ${sec} s`;
  return `${sec} s`;
}

/**
 * Avertissement avant une action qui interrompt la réponse en cours (fermer l'onglet, nouvelle conversation…) ;
 * undefined si rien n'est en cours, donc aucune confirmation à demander.
 */
export function busyWarning(streaming: boolean, queued: number, consequence: string): string | undefined {
  if (!streaming) return undefined;
  const lost = queued === 0 ? '' : queued === 1 ? '\n\n1 message en attente sera perdu.' : `\n\n${queued} messages en attente seront perdus.`;
  return `Claude est en train de répondre. ${consequence}${lost}`;
}
