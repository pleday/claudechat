/**
 * Choix du message de l'utilisateur épinglé en haut de la conversation : le dernier, sauf si c'est une simple demande
 * de reprise (« continue », « reprends là où tu t'étais arrêté »…) qui n'a aucun sens seule : on épingle alors le
 * message précédent, celui qui contient la tâche.
 */
export interface StickyChoice { prompt: string; resumedWith?: string }

const TRIGGERS = new Set([
  'reprend', 'reprends', 'reprendre', 'reprenez', 'continue', 'continues', 'continuer', 'continuez', 'poursuis', 'poursuivre',
  'poursuivez', 'recommence', 'recommences', 'recommencer', 'recommencez', 'relance', 'relances', 'relancer', 'reessaie',
  'reessaye', 'reessayer', 'retente', 'retenter', 'resume', 'retry', 'proceed',
]);

const PHRASES = ['vas y', 'allez y', 'on continue', 'go on', 'go ahead', 'keep going', 'carry on', 'try again', 'essaie encore', 'c est reparti'];

/** Mots sans contenu propre : une demande de reprise n'est faite que de ceux-là et d'un verbe de reprise. */
const FILLER = new Set([
  'oui', 'ok', 'okay', 'stp', 'svp', 's', 'il', 'te', 'vous', 'plait', 'merci', 'maintenant', 'donc', 'alors', 'bon', 'allez',
  'la', 'ou', 'tu', 't', 'etais', 'etait', 'arrete', 'arretes', 'ta', 'ton', 'tache', 'travail', 'on', 'y', 'vas', 'va',
  'de', 'du', 'des', 'le', 'les', 'toi', 'claude', 'encore', 'une', 'fois', 'juste', 'please', 'pls', 'now', 'again', 'where',
  'you', 'left', 'off', 'the', 'task', 'it', 'ahead', 'e', 'avait', 'stoppe', 'interrompu', 'ca', 'cela', 'tout', 'au', 'meme', 'point',
]);

const tokens = (s: string): string[] =>
  s.toLowerCase().normalize('NFD').replace(/\p{Mn}+/gu, '').split(/[^a-z0-9]+/).filter(Boolean);

/** Vrai pour un message qui ne fait que demander de reprendre ce qui était interrompu. */
export function isResume(text: string): boolean {
  const t = tokens(text);
  if (!t.length || t.length > 14) return false;
  const joined = t.join(' ');
  // reprise automatique après quota (envoyée par le plugin)
  if (joined.startsWith('le quota est reinitialise')) return true;
  let rest = t;
  const phrase = PHRASES.find((p) => ` ${joined} `.includes(` ${p} `));
  const triggered = t.some((x) => TRIGGERS.has(x)) || phrase !== undefined;
  if (!triggered) return false;
  if (phrase) rest = ` ${joined} `.replace(` ${phrase} `, ' ').trim().split(' ').filter(Boolean);
  return rest.every((x) => TRIGGERS.has(x) || FILLER.has(x));
}

/** `userMessages` : les messages de l'utilisateur, du plus ancien au plus récent ; undefined s'il n'y en a pas. */
export function pickSticky(userMessages: string[]): StickyChoice | undefined {
  const last = userMessages.at(-1);
  if (last === undefined) return undefined;
  if (!isResume(last)) return { prompt: last };
  const i = userMessages.findLastIndex((m) => !isResume(m));
  return i < 0 ? { prompt: last } : { prompt: userMessages[i], resumedWith: last };
}
