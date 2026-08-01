import { describe, it, expect } from 'vitest';
import { formatAddress, cleanTunisianAdminName } from './format';

describe('cleanTunisianAdminName', () => {
  it('drops the administrative prefixes a dispatcher does not read', () => {
    expect(cleanTunisianAdminName('Gouvernorat Sfax')).toBe('Sfax');
    expect(cleanTunisianAdminName('Délégation Sfax Ouest')).toBe('Sfax Ouest');
    expect(cleanTunisianAdminName('Delegation Sidi Hassine')).toBe('Sidi Hassine');
  });

  it('leaves a name that merely contains one of those words alone', () => {
    expect(cleanTunisianAdminName('Rue du Gouvernorat')).toBe('Rue du Gouvernorat');
  });
});

describe('formatAddress', () => {
  it('reduces the address that prompted this', () => {
    // Straight from the database: reverse geocoding's full administrative path, with the order's
    // own city and postcode appended twice more on top of it.
    const raw = 'Rue de Kerbala, Markez Chaker, Merkez Chaker, Délégation Sfax Ouest, '
      + 'Gouvernorat Sfax, 3003, Tunisie, sfax, sfax 3003,, sfax, 3003';
    expect(formatAddress(raw)).toBe('Rue de Kerbala, Markez Chaker, Sfax Ouest, Sfax, 3003');
  });

  it('keeps a short address untouched', () => {
    // Nothing to remove is the common case, and it must come through character for character.
    expect(formatAddress('54 rue Ibn Khaldoun, 7000')).toBe('54 rue Ibn Khaldoun, 7000');
  });

  it('removes the country', () => {
    expect(formatAddress('Avenue Habib Bourguiba, Tunis, Tunisie')).toBe('Avenue Habib Bourguiba, Tunis');
    expect(formatAddress('Main St, Tunis, Tunisia')).toBe('Main St, Tunis');
  });

  it('treats a repeat as a repeat whatever its case or accents', () => {
    expect(formatAddress('Sfax, SFAX, sfax')).toBe('Sfax');
    expect(formatAddress('Béja, Beja')).toBe('Béja');
  });

  it('collapses two romanisations of one place name', () => {
    // Nominatim returns both spellings for the same locality; only the vowels differ.
    expect(formatAddress('Markez Chaker, Merkez Chaker')).toBe('Markez Chaker');
  });

  it('drops a segment that only recombines segments already present', () => {
    expect(formatAddress('Sfax, 3003, sfax 3003')).toBe('Sfax, 3003');
  });

  it('keeps a compound whose parts are not all already there', () => {
    // "Sfax Ville" earns its place: "Ville" has not been said.
    expect(formatAddress('Sfax, 3003, Sfax Ville')).toBe('Sfax, 3003, Sfax Ville');
  });

  it('swallows empty segments rather than printing the gap', () => {
    expect(formatAddress('Rue A,, Tunis')).toBe('Rue A, Tunis');
    expect(formatAddress('  ,  ,  ')).toBe('');
  });

  it('answers with an empty string for nothing at all', () => {
    expect(formatAddress(null)).toBe('');
    expect(formatAddress(undefined)).toBe('');
    expect(formatAddress('')).toBe('');
  });
});
