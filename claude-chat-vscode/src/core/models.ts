import type { CliModel } from './cliInfo';

/**
 * Catalogue des modèles. Les modèles viennent de la CLI (réponse `initialize`, voir update) : nouveaux modèles,
 * noms et niveaux d'effort pris en charge sont ainsi toujours à jour. La liste figée ne sert que de secours
 * tant que la CLI n'a pas répondu (ou si elle est injoignable).
 */
const fallback: Array<[string, string]> = [
  ['claude-fable-5-2', 'Fable 5.2'],
  ['claude-opus-5-5', 'Opus 5.5'],
  ['claude-sonnet-5-5', 'Sonnet 5.5'],
  ['claude-haiku-5-5', 'Haiku 5.5'],
];
const fallbackAliases: Record<string, string> = {
  fable: 'claude-fable-5-2', opus: 'claude-opus-5-5', sonnet: 'claude-sonnet-5-5', haiku: 'claude-haiku-5-5',
};

export class ModelCatalog {
  private fromCli: CliModel[] = [];

  /** Retient les modèles annoncés par la CLI ; une liste vide (CLI muette) ne remplace pas la précédente. */
  update(models: CliModel[]): void { if (models.length) this.fromCli = models; }

  /** Modèles annoncés par la CLI ; vide tant qu'elle n'a pas répondu. */
  all(): CliModel[] { return this.fromCli; }

  /** [identifiant, nom] : ceux de la CLI, sinon la liste de secours. */
  known(): Array<[string, string]> { return this.fromCli.length ? this.fromCli.map((m) => [m.id, m.name]) : fallback; }

  description(id: string): string | undefined { return this.fromCli.find((m) => m.id === id)?.description || undefined; }

  /**
   * Niveaux d'effort proposés pour `id` : « auto » puis ceux que le modèle accepte ; seulement « auto » s'il n'en
   * accepte aucun. Modèle inconnu de la CLI (personnalisé, CLI muette) : `fallbackLevels`, sans restriction.
   */
  effortChoices(id: string, fallbackLevels: string[]): string[] {
    const m = this.fromCli.find((x) => x.id === id);
    return m ? ['auto', ...m.efforts] : fallbackLevels;
  }

  /** Alias (« sonnet ») -> identifiant complet ; inchangé sinon. */
  resolve(m: string): string {
    const key = m.trim();
    return this.fromCli.find((x) => x.alias.toLowerCase() === key.toLowerCase())?.id ?? fallbackAliases[key.toLowerCase()] ?? key;
  }

  /** « Sonnet 5.5 » pour un identifiant connu, sinon déduit de claude-<famille>-<x>-<y>[-date], sinon l'identifiant brut. */
  label(id: string): string {
    const k = this.known().find((x) => x[0] === id);
    if (k) return k[1];
    const m = /^claude-([a-z]+)-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?$/.exec(id);
    if (!m) return id;
    const v = m[2] + (m[3] ? `.${m[3]}` : '');
    return m[1][0].toUpperCase() + m[1].slice(1) + ' ' + v;
  }

  display(id: string): string { const l = this.label(id); return l === id ? id : `${l}  (${id})`; }
}
