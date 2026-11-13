import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { exec, type Json } from './cli';

/** Portée d'un serveur MCP, comme dans la CLI (`-s user|project|local`) ; claude.ai et « autres » ne sont pas modifiables ici. */
export type McpScope = 'user' | 'project' | 'local' | 'other' | 'claude_ai';
export const MCP_SCOPES: Record<McpScope, { title: string; source: string; removable: boolean }> = {
  user: { title: 'Utilisateur', source: 'mcpServers dans ~/.claude.json', removable: true },
  project: { title: 'Projet', source: '.mcp.json (partagé avec l\'équipe)', removable: true },
  local: { title: 'Local', source: 'ce projet, privé (~/.claude.json)', removable: true },
  other: { title: 'Autres', source: 'fournis par un plugin ou la CLI', removable: false },
  claude_ai: { title: 'claude.ai', source: 'connecteurs de ton compte claude.ai', removable: false },
};

export type McpStatus = 'checking' | 'connected' | 'needs_auth' | 'failed' | 'pending';

export interface McpServer {
  name: string;
  scope: McpScope;
  target: string;
  type?: string;
  status: McpStatus;
  headers: Record<string, string>;
  env: Record<string, string>;
}

export interface ListedServer { name: string; target: string; type?: string; status: McpStatus }
export interface Normalized { servers: Array<[string, string]>; error?: string }

export const namePattern = /^[A-Za-z0-9_.-]+$/;

export function statusOf(text: string): McpStatus {
  const t = text.toLowerCase();
  if (t.includes('needs auth')) return 'needs_auth';
  if (t.includes('pending')) return 'pending';
  if (t.includes('fail') || t.includes('error')) return 'failed';
  if (t.includes('connected')) return 'connected';
  return 'failed';
}

/** Analyse la sortie de `claude mcp list` : « nom: cible (TYPE) - statut ». */
export function parseList(out: string): ListedServer[] {
  const res: ListedServer[] = [];
  for (const line of out.split('\n')) {
    const i = line.lastIndexOf(' - ');
    if (i < 0) continue;
    const head = line.slice(0, i);
    const j = head.indexOf(': ');
    if (j <= 0) continue;
    let target = head.slice(j + 2).trim();
    let type: string | undefined;
    const m = /^(.*) \(([A-Za-z]+)\)$/.exec(target);
    if (m) { target = m[1]; type = m[2].toLowerCase(); }
    res.push({ name: head.slice(0, j).trim(), target, type, status: statusOf(line.slice(i + 3)) });
  }
  return res;
}

const strings = (o: unknown): Record<string, string> => {
  const r: Record<string, string> = {};
  if (o && typeof o === 'object') for (const [k, v] of Object.entries(o)) r[k] = typeof v === 'string' ? v : '';
  return r;
};

function serverFrom(name: string, scope: McpScope, o: Json): McpServer {
  const url = typeof o.url === 'string' ? o.url : undefined;
  const command = typeof o.command === 'string' ? o.command : undefined;
  const args = Array.isArray(o.args) ? o.args.map(String).join(' ') : '';
  const type = typeof o.type === 'string' ? o.type : url ? 'http' : 'stdio';
  return { name, scope, target: url ?? [command, args || undefined].filter(Boolean).join(' '), type, status: 'checking', headers: strings(o.headers), env: strings(o.env) };
}

const serversIn = (obj: Json | undefined, scope: McpScope): McpServer[] => {
  const m = obj?.mcpServers;
  if (!m || typeof m !== 'object') return [];
  return Object.entries<unknown>(m).filter(([, v]) => v && typeof v === 'object').map(([k, v]) => serverFrom(k, scope, v as Json));
};

const readObject = (p: string): Json | undefined => {
  try { const j = JSON.parse(fs.readFileSync(p, 'utf8')); return j && typeof j === 'object' ? j : undefined; } catch { return undefined; }
};

/** Lit les trois fichiers de configuration (utilisateur, local, projet) ; statuts « en vérification ». */
export function readConfigs(cwd: string, claudeJson = path.join(os.homedir(), '.claude.json'), projectFile = path.join(cwd, '.mcp.json')): McpServer[] {
  const root = readObject(claudeJson);
  const local = root?.projects?.[cwd];
  const project = readObject(projectFile);
  return [...serversIn(root, 'user'), ...serversIn(project, 'project'), ...serversIn(local && typeof local === 'object' ? local : undefined, 'local')];
}

/** Applique les statuts de `mcp list` aux serveurs configurés ; les autres sont rangés sous claude.ai / Autres. */
export function merge(configs: McpServer[], listed: ListedServer[]): McpServer[] {
  const byName = new Map(listed.map((l) => [l.name, l]));
  const known = configs.map((c): McpServer => {
    const l = byName.get(c.name);
    return l ? { ...c, status: l.status, target: c.target || l.target, type: c.type ?? l.type } : { ...c, status: 'failed' };
  });
  const names = new Set(configs.map((c) => c.name));
  const extra = listed.filter((l) => !names.has(l.name)).map((l): McpServer => ({
    name: l.name, scope: l.name.startsWith('claude.ai ') ? 'claude_ai' : 'other', target: l.target, type: l.type, status: l.status, headers: {}, env: {},
  }));
  return [...known, ...extra];
}

/** Valeur masquée d'un en-tête ou d'une variable (jamais affichée en clair). */
export function mask(value: string): string {
  const first = value.split(' ')[0];
  return value.includes(' ') && ['Bearer', 'Basic', 'Token'].includes(first) ? `${first} ••••••` : '••••••';
}

const isServer = (e: unknown) => !!e && typeof e === 'object' && ('command' in (e as object) || 'url' in (e as object));

/**
 * Accepte une configuration `{"mcpServers": {...}}`, un objet `{nom: serveur}` ou la configuration d'un seul serveur
 * (le nom est alors obligatoire). Renvoie la liste (nom, JSON du serveur) ou une erreur lisible.
 */
export function normalize(text: string, name: string): Normalized {
  let root: unknown;
  try { root = JSON.parse(text.trim()); } catch (e) {
    return { servers: [], error: `JSON invalide : ${(e as Error).message.split('\n')[0].slice(0, 120)}` };
  }
  if (!root || typeof root !== 'object' || Array.isArray(root)) return { servers: [], error: 'La configuration doit être un objet JSON.' };
  const obj = root as Json;
  let map: Json | null;
  if (obj.mcpServers && typeof obj.mcpServers === 'object') map = obj.mcpServers;
  else if (isServer(obj)) map = null;
  else if (Object.keys(obj).length > 0 && Object.values(obj).every(isServer)) map = obj;
  else return { servers: [], error: 'Aucun serveur trouvé : attendu « command » (stdio) ou « url » (http / sse).' };
  if (map === null) {
    const n = name.trim();
    if (!n) return { servers: [], error: 'Donne un nom au serveur (champ « Nom »).' };
    if (!namePattern.test(n)) return { servers: [], error: 'Nom invalide : lettres, chiffres, « - », « _ » et « . » uniquement.' };
    return { servers: [[n, JSON.stringify(obj)]] };
  }
  const entries = Object.entries(map);
  if (!entries.length) return { servers: [], error: '« mcpServers » est vide.' };
  const bad = entries.find(([, v]) => !isServer(v));
  if (bad) return { servers: [], error: `Le serveur « ${bad[0]} » n'a ni « command » ni « url ».` };
  const invalid = entries.find(([k]) => !namePattern.test(k));
  if (invalid) return { servers: [], error: `Nom de serveur invalide : « ${invalid[0]} ».` };
  return { servers: entries.map(([k, v]) => [k, JSON.stringify(v)]) };
}

// ---------- Appels à la CLI ----------

export async function list(cli: string, cwd: string): Promise<ListedServer[]> {
  return parseList((await exec(cli, ['mcp', 'list'], { cwd })).out);
}

export async function add(cli: string, cwd: string, name: string, json: string, scope: McpScope): Promise<[boolean, string]> {
  const r = await exec(cli, ['mcp', 'add-json', '-s', scope, name, json], { cwd });
  return [r.code === 0, r.out || 'Ajouté.'];
}

export async function remove(cli: string, cwd: string, name: string, scope: McpScope): Promise<[boolean, string]> {
  const r = await exec(cli, ['mcp', 'remove', name, '-s', scope], { cwd });
  return [r.code === 0, r.out || 'Supprimé.'];
}
