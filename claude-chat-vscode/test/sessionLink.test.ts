import { describe, expect, it } from 'vitest';
import { parseSessionLink } from '../src/core/sessionLink';

describe('parseSessionLink', () => {
  const id = '65f47066-1c3d-445a-b2b1-8106dd96a66f';

  it('lit l\'identifiant de la session', () => {
    expect(parseSessionLink('/session', `id=${id}`)).toEqual({ id });
  });

  it('lit le dossier du projet quand il est donné', () => {
    expect(parseSessionLink('/session', `id=${id}&cwd=${encodeURIComponent('/home/me/projet')}`)).toEqual({ id, cwd: '/home/me/projet' });
  });

  it('accepte une barre finale', () => {
    expect(parseSessionLink('/session/', `id=${id}`)).toEqual({ id });
  });

  it('refuse un autre chemin', () => {
    expect(parseSessionLink('/autre', `id=${id}`)).toBeNull();
  });

  it('refuse un identifiant absent ou qui n\'est pas celui d\'une session', () => {
    expect(parseSessionLink('/session', '')).toBeNull();
    expect(parseSessionLink('/session', 'id=../../etc/passwd')).toBeNull();
    expect(parseSessionLink('/session', 'id=abc')).toBeNull();
  });

  it('ignore un dossier vide', () => {
    expect(parseSessionLink('/session', `id=${id}&cwd=%20`)).toEqual({ id });
  });
});
