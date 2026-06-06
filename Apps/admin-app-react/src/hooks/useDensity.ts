import { useState } from 'react';

export type Density = 'compact' | 'comfortable' | 'spacious';

export function useDensity(key: string, defaultDensity: Density = 'comfortable') {
  const [density, setDensityState] = useState<Density>(() => {
    try {
      const stored = localStorage.getItem(`table-density:${key}`);
      if (stored === 'compact' || stored === 'comfortable' || stored === 'spacious') {
        return stored as Density;
      }
    } catch { /* ignore */ }
    return defaultDensity;
  });

  const setDensity = (d: Density) => {
    setDensityState(d);
    try { localStorage.setItem(`table-density:${key}`, d); } catch { /* ignore */ }
  };

  return { density, setDensity };
}
