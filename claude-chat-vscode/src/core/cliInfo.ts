import type { Json } from './cli';

/** Modèle proposé par la CLI (réponse `initialize`). `efforts` vide : le modèle n'accepte aucun niveau d'effort. */
export interface CliModel { id: string; alias: string; name: string; description: string; efforts: string[] }

/** Un alias court (« sonnet ») par opposition à une version précise (« claude-opus-4-6 »). */
export const isAlias = (m: CliModel) => !m.alias.startsWith('claude-');

/** Commande slash exposée par la CLI (natives, personnalisées, skills, plugins). */
export interface CliCommand { name: string; description: string }

const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined);

/** Charge utile d'une réponse de contrôle (`response.response`), ou undefined si le format est inattendu. */
export function controlPayload(j: Json): Json | undefined {
  const p = j?.response?.response;
  return p && typeof p === 'object' && !Array.isArray(p) ? p : undefined;
}

/**
 * Modèles d'une réponse `initialize`. L'entrée « default » est ignorée (elle double un autre modèle) et les doublons
 * sont retirés sur l'identifiant résolu, l'alias court passant avant la version précise.
 */
export function parseModels(j: Json): CliModel[] {
  const arr = controlPayload(j)?.models;
  if (!Array.isArray(arr)) return [];
  const seen = new Set<string>();
  const out: CliModel[] = [];
  for (const o of arr) {
    if (!o || typeof o !== 'object') continue;
    const alias = str(o.value);
    if (!alias?.trim() || alias === 'default') continue;
    const id = str(o.resolvedModel)?.trim() || alias;
    const efforts = o.supportsEffort === true && Array.isArray(o.supportedEffortLevels)
      ? o.supportedEffortLevels.filter((e: unknown): e is string => typeof e === 'string') : [];
    if (seen.has(id)) continue;
    seen.add(id);
    out.push({ id, alias, name: str(o.displayName) ?? id, description: str(o.description) ?? '', efforts });
  }
  return out;
}

/** Extrait les commandes d'une réponse `initialize` ; vide si le format est inattendu. */
export function parseCommands(j: Json): CliCommand[] {
  const arr = controlPayload(j)?.commands;
  if (!Array.isArray(arr)) return [];
  const out: CliCommand[] = [];
  for (const o of arr) {
    const name = str(o?.name);
    if (!name?.trim()) continue;
    out.push({ name, description: (str(o.description) ?? '').replace(/\s+/g, ' ') });
  }
  return out;
}

/** Une ligne du détail du contexte : `kind` vaut used, deferred, buffer ou free. */
export interface ContextCategory { id: string; name: string; tokens: number; kind: string }

/** Contexte d'une conversation tel que mesuré par la CLI (`get_context_usage`). */
export interface ContextUsage {
  total: number;
  max: number;
  model?: string;
  categories: ContextCategory[];
  autoCompactAt?: number;
  /** Répartition des messages : outils (appels, résultats), pièces jointes, réponses de Claude, messages de l'utilisateur. */
  toolCalls: number; toolResults: number; attachments: number; assistant: number; user: number;
  /** Mesuré sur un processus à part, sans les serveurs MCP : leurs outils ne sont pas comptés. */
  approximate: boolean;
}

export const contextPercent = (u: ContextUsage) => (u.max > 0 ? Math.round((u.total * 100) / u.max) : 0);

const CONTEXT_LABELS: Record<string, string> = {
  system_prompt: 'Prompt système', system_tools: 'Outils système', system_tools_deferred: 'Outils système (différés)',
  skills: 'Skills', messages: 'Messages', memory_files: 'Fichiers mémoire (CLAUDE.md)', mcp_tools: 'Outils MCP',
  custom_agents: 'Agents', agents: 'Agents', autocompact_buffer: 'Réserve de compaction', free_space: 'Espace libre',
};

/** `payload` : la charge utile de la réponse `get_context_usage` (voir controlPayload). */
export function parseContextUsage(payload: Json): ContextUsage | undefined {
  const int = (o: Json | undefined, k: string) => (typeof o?.[k] === 'number' ? (o[k] as number) : 0);
  const max = int(payload, 'maxTokens');
  if (max <= 0) return undefined;
  const categories: ContextCategory[] = [];
  if (Array.isArray(payload.categories)) for (const o of payload.categories) {
    const id = str(o?.id);
    if (!id) continue;
    categories.push({ id, name: CONTEXT_LABELS[id] ?? str(o.name) ?? id, tokens: int(o, 'tokens'), kind: str(o.kind) ?? 'used' });
  }
  const mb = payload.messageBreakdown && typeof payload.messageBreakdown === 'object' ? payload.messageBreakdown : undefined;
  return {
    total: int(payload, 'totalTokens'), max, model: str(payload.model), categories,
    autoCompactAt: typeof payload.autoCompactThreshold === 'number' && payload.isAutoCompactEnabled !== false ? payload.autoCompactThreshold : undefined,
    toolCalls: int(mb, 'toolCallTokens'), toolResults: int(mb, 'toolResultTokens'), attachments: int(mb, 'attachmentTokens'),
    assistant: int(mb, 'assistantMessageTokens'), user: int(mb, 'userMessageTokens'),
    approximate: false,
  };
}

/** « 134,6k », « 1,0M », « 842 » (format français, indépendant de la langue de la machine). */
export function fmtTokens(n: number): string {
  const f = (v: number, s: string) => v.toFixed(1).replace('.', ',') + s;
  if (n >= 1_000_000) return f(n / 1e6, 'M');
  if (n >= 1000) return f(n / 1000, 'k');
  return String(n);
}
