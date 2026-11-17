/** Lien vers une conversation : `vscode://pleday.claude-chat-vscode/session?id=<session>[&cwd=<dossier>]`. */
export interface SessionLink {
  /** Identifiant de la session de la CLI. */
  id: string;
  /** Dossier du projet de la session, quand il diffère de celui de la fenêtre. */
  cwd?: string;
}

/** Identifiants de session de la CLI (UUID) ; refuse tout ce qui pourrait sortir du dossier des sessions. */
const SESSION_ID = /^[0-9A-Za-z-]{8,64}$/;

/** Lit le chemin et la requête d'un lien ; null si ce n'est pas un lien de session valide. */
export function parseSessionLink(path: string, query: string): SessionLink | null {
  if (path.replace(/\/+$/, '') !== '/session') return null;
  const params = new URLSearchParams(query);
  const id = params.get('id') ?? '';
  if (!SESSION_ID.test(id)) return null;
  const cwd = params.get('cwd')?.trim();
  return cwd ? { id, cwd } : { id };
}
