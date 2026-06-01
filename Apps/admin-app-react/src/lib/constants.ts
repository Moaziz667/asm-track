type PostalLocality = { code: string; name: string; lat: number; lng: number };
type PostalGovernorate = { governorate: string; localities: PostalLocality[] };

export const TUNISIAN_POSTAL_CODES: PostalGovernorate[] = [
  {
    governorate: 'Tunis',
    localities: [
      { code: '1000', name: 'Tunis Centre', lat: 36.8065, lng: 10.1815 },
      { code: '1001', name: 'Bab Bhar', lat: 36.7997, lng: 10.1764 },
      { code: '1002', name: 'La Kasbah', lat: 36.7971, lng: 10.1691 },
      { code: '1006', name: 'Bab Souika', lat: 36.8122, lng: 10.1650 },
      { code: '1053', name: 'Berges du Lac', lat: 36.8375, lng: 10.2333 },
    ],
  },
  {
    governorate: 'Ariana',
    localities: [
      { code: '2000', name: 'Bardo/Ariana', lat: 36.8665, lng: 10.1647 },
      { code: '2035', name: 'Raoued', lat: 36.9080, lng: 10.1700 },
      { code: '2080', name: 'Ariana Ville', lat: 36.8620, lng: 10.1950 },
      { code: '2083', name: 'Cite Ghazela', lat: 36.8850, lng: 10.1800 },
    ],
  },
  {
    governorate: 'Ben Arous',
    localities: [
      { code: '2013', name: 'Ben Arous', lat: 36.7531, lng: 10.2227 },
      { code: '2050', name: 'Hammam Lif', lat: 36.7210, lng: 10.3340 },
      { code: '2060', name: 'Mourouj', lat: 36.7450, lng: 10.2500 },
      { code: '2078', name: 'La Medina Jedida', lat: 36.7600, lng: 10.2100 },
    ],
  },
  {
    governorate: 'Manouba',
    localities: [
      { code: '2010', name: 'Manouba', lat: 36.8070, lng: 10.0880 },
      { code: '2011', name: 'Den Den', lat: 36.8050, lng: 10.1000 },
      { code: '2012', name: 'Douar Hicher', lat: 36.8200, lng: 10.0900 },
    ],
  },
  {
    governorate: 'Nabeul',
    localities: [
      { code: '8000', name: 'Nabeul', lat: 36.4561, lng: 10.7376 },
      { code: '8050', name: 'Hammamet', lat: 36.3986, lng: 10.6122 },
      { code: '8090', name: 'Kelibia', lat: 36.8470, lng: 11.0850 },
    ],
  },
];

export const POSTAL_CODE_NAMES: Record<string, string> = Object.fromEntries(
  TUNISIAN_POSTAL_CODES.flatMap((g) => g.localities.map((l) => [l.code, l.name])),
);
