/**
 * Turning a stored postal address into something a dispatcher reads.
 *
 * Shared rather than local to one page: the list, the delivery detail, the dispatch queue,
 * the handoff modal and the route map all print the same stored value, and they were drifting
 * apart one screen at a time.
 */

/** Strip the Tunisian admin prefixes ("Gouvernorat ", "Délégation ") for compact display. */
export function cleanTunisianAdminName(name: string | null | undefined): string {
  if (!name) return '';
  if (name.startsWith('Gouvernorat ')) return name.substring('Gouvernorat '.length);
  if (name.startsWith('Délégation ')) return name.substring('Délégation '.length);
  if (name.startsWith('Delegation ')) return name.substring('Delegation '.length);
  return name;
}

/** Lower-case, accent-free, whitespace-collapsed — for comparing, never for display. */
function fold(v: string): string {
  return v.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/\s+/g, ' ').trim();
}

/** The consonants alone. Reverse geocoding returns Tunisian place names in more than one
 *  romanisation — "Markez Chaker" and "Merkez Chaker" are the same place, spelled by two
 *  different transliterations, and only the vowels move. */
function consonants(v: string): string {
  return fold(v).replace(/[aeiouy]/g, '');
}

const COUNTRY_NAMES = new Set(['tunisie', 'tunisia', 'تونس']);

/**
 * Makes a stored address readable.
 *
 * Reverse geocoding gives a full administrative path, and the order's own city and postcode are
 * appended to it, so the same place arrives three times over:
 *
 *   Rue de Kerbala, Markez Chaker, Merkez Chaker, Délégation Sfax Ouest, Gouvernorat Sfax,
 *   3003, Tunisie, sfax, sfax 3003,, sfax, 3003
 *
 * Nothing here invents or reorders: it drops what is repeated, the administrative prefixes a
 * dispatcher does not read, and the country — this is a Tunisian operation, and "Tunisie" on
 * every line is one word that never distinguishes two addresses.
 *
 * The full stored value stays available as the element's title; this is a display formatter, not
 * a migration.
 */
export function formatAddress(raw: string | null | undefined): string {
  if (!raw) return '';

  const kept: string[] = [];
  const seen = new Set<string>();      // exact matches, folded
  const skeletons = new Set<string>(); // transliteration twins
  const words = new Set<string>();     // for spotting a segment that only repeats other segments

  for (const piece of raw.split(',')) {
    const segment = cleanTunisianAdminName(piece.trim());
    if (!segment) continue;                       // the ",," in the middle
    const key = fold(segment);
    if (!key || COUNTRY_NAMES.has(key)) continue;
    if (seen.has(key)) continue;

    const skeleton = consonants(segment);
    if (skeleton && skeletons.has(skeleton)) continue;

    // "sfax 3003" once "sfax" and "3003" are both already there: a recombination of what the
    // reader has, adding a line and no information.
    const parts = key.split(' ');
    if (parts.length > 1 && parts.every(w => words.has(w))) continue;

    kept.push(segment);
    seen.add(key);
    if (skeleton) skeletons.add(skeleton);
    for (const w of parts) words.add(w);
  }

  return kept.join(', ');
}
