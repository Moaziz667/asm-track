import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type OperationalFilters = {
  dateFrom: string;
  dateTo: string;
  zone: string;
  driver: string;
  route: string;
  status: string;
  search: string;
};

export const DEFAULT_OPERATIONAL_FILTERS: OperationalFilters = {
  dateFrom: '',
  dateTo: '',
  zone: '',
  driver: '',
  route: '',
  status: '',
  search: '',
};

export const FILTER_PRESET_KEYS = ['morning-dispatch', 'north-zone', 'at-risk-routes'] as const;

type FilterPresetKey = (typeof FILTER_PRESET_KEYS)[number];

type PresetStore = {
  [key in FilterPresetKey]?: OperationalFilters;
};

type GlobalFilterState = {
  filters: OperationalFilters;
  globalContext: boolean;
  presets: PresetStore;
  setFilter: (key: keyof OperationalFilters, value: string) => void;
  applyFilters: (next: Partial<OperationalFilters>) => void;
  clearFilters: () => void;
  setGlobalContext: (enabled: boolean) => void;
  savePreset: (key: FilterPresetKey) => void;
  loadPreset: (key: FilterPresetKey) => void;
};

export const useGlobalFilters = create<GlobalFilterState>()(
  persist(
    (set, get) => ({
      filters: DEFAULT_OPERATIONAL_FILTERS,
      globalContext: true,
      presets: {},
      setFilter: (key, value) =>
        set((state) => ({
          filters: {
            ...state.filters,
            [key]: value,
          },
        })),
      applyFilters: (next) =>
        set((state) => ({
          filters: {
            ...state.filters,
            ...next,
          },
        })),
      clearFilters: () =>
        set({
          filters: DEFAULT_OPERATIONAL_FILTERS,
        }),
      setGlobalContext: (enabled) => set({ globalContext: enabled }),
      savePreset: (key) => {
        const current = get().filters;
        set((state) => ({
          presets: {
            ...state.presets,
            [key]: current,
          },
        }));
      },
      loadPreset: (key) => {
        const preset = get().presets[key];
        if (!preset) return;
        set({ filters: preset });
      },
    }),
    {
      name: 'admin-operational-filters',
      partialize: (state) => ({
        filters: state.filters,
        globalContext: state.globalContext,
        presets: state.presets,
      }),
    }
  )
);

export function isOperationalRoute(pathname: string) {
  return (
    pathname.startsWith('/command-center') ||
    pathname.startsWith('/routes') ||
    pathname.startsWith('/deliveries') ||
    pathname.startsWith('/dispatch-desk')
  );
}
