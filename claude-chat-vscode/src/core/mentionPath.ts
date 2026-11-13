import * as path from 'node:path';

/**
 * Chemin absolu normalisé d'une mention `@chemin`. Avec `projectOnly` (message venu du téléphone), une mention qui sort
 * du projet (chemin absolu ailleurs, `..`) est refusée : undefined. Sinon tout chemin est accepté (saisie dans l'éditeur).
 */
export function resolveMention(rel: string, cwd: string, projectOnly: boolean): string | undefined {
  const base = path.resolve(cwd);
  const p = path.resolve(base, rel);
  if (projectOnly && p !== base && !p.startsWith(base + path.sep)) return undefined;
  return p;
}
