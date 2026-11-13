import data from '../../../shared/data/slash-commands.json';
import tipsData from '../../../shared/data/tips.json';
import type { CliCommand } from './cliInfo';

// Données communes avec le plugin JetBrains : ../shared/data (une seule source pour les deux projets).

/** Une commande slash proposée à la saisie, avec sa catégorie et sa description. */
export interface SlashEntry { name: string; category: string; description: string }

export const SlashCatalog = {
  PLUGIN: data.pluginCategory,
  /** Ordre d'affichage des groupes. */
  ORDER: data.categories as string[],
  SKILLS: 'Skills et extensions',
  PLUGINS: 'Plugins',

  /** Liste complète : raccourcis du plugin d'abord, puis la CLI ; le plugin l'emporte en cas de doublon. */
  build(cli: CliCommand[]): SlashEntry[] {
    const out = new Map<string, SlashEntry>();
    for (const [n, d] of Object.entries(data.local)) out.set(n, { name: n, category: data.pluginCategory, description: d });
    const hidden = new Set(data.hidden);
    const known = data.known as Record<string, { category: string; description: string }>;
    for (const c of cli) {
      if (out.has(c.name) || hidden.has(c.name) || c.name.startsWith('__')) continue;
      const k = known[c.name];
      const category = k?.category ?? (c.name.includes(':') ? SlashCatalog.PLUGINS : SlashCatalog.SKILLS);
      out.set(c.name, { name: c.name, category, description: k?.description ?? c.description });
    }
    return [...out.values()];
  },

  /** Entrées dont le nom commence par `query`, regroupées par catégorie (ordre ORDER) puis triées par nom. */
  group(query: string, entries: SlashEntry[]): Array<[string, SlashEntry[]]> {
    const groups = new Map<string, SlashEntry[]>();
    for (const e of entries) {
      if (!e.name.toLowerCase().startsWith(query.toLowerCase())) continue;
      (groups.get(e.category) ?? groups.set(e.category, []).get(e.category)!).push(e);
    }
    return [...groups.entries()]
      .map(([c, l]): [string, SlashEntry[]] => [c, l.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))])
      .sort((a, b) => SlashCatalog.ORDER.indexOf(a[0]) - SlashCatalog.ORDER.indexOf(b[0]));
  },
};

/** Astuces affichées à l'ouverture d'une nouvelle conversation, par groupes de trois. */
export const Tips = {
  GROUP: 3,
  all: tipsData as string[],
  get groups(): number { return Math.ceil(Tips.all.length / Tips.GROUP); },
  /** Les trois astuces du groupe `i` (le groupe boucle). */
  group(i: number): string[] {
    const start = ((i % Tips.groups) + Tips.groups) % Tips.groups * Tips.GROUP;
    return Array.from({ length: Tips.GROUP }, (_, k) => Tips.all[(start + k) % Tips.all.length]);
  },
};
