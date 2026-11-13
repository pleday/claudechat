/**
 * Historique des messages envoyés, rappelés avec ↑ / ↓ dans le champ de saisie (comme un terminal).
 * Le texte en cours de frappe est conservé comme brouillon et restitué en redescendant.
 */
export class InputHistory {
  private entries: string[] = [];
  private pos = 0; // == entries.length : on n'est pas dans l'historique
  private draft = '';

  constructor(private max = 50) {}

  add(text: string): void {
    const t = text.trim();
    if (t && this.entries.at(-1) !== t) {
      this.entries.push(t);
      if (this.entries.length > this.max) this.entries.shift();
    }
    this.pos = this.entries.length; this.draft = '';
  }

  /** Message précédent, ou undefined s'il n'y en a plus. `current` est le texte actuel du champ. */
  previous(current: string): string | undefined {
    // texte modifié pendant la navigation : il devient le nouveau brouillon
    if (this.pos === this.entries.length || current !== this.entries[this.pos]) { this.draft = current; this.pos = this.entries.length; }
    if (this.pos === 0) return undefined;
    return this.entries[--this.pos];
  }

  /** Message suivant (ou le brouillon en bout de course), ou undefined si on n'est pas dans l'historique. */
  next(current: string): string | undefined {
    if (this.pos >= this.entries.length) return undefined;
    if (current !== this.entries[this.pos]) return undefined;
    this.pos++;
    return this.pos === this.entries.length ? this.draft : this.entries[this.pos];
  }
}
