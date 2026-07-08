import { create } from 'zustand';
import { persist } from 'zustand/middleware';

type GlobalMapState = {
  focusedRouteId: string | null;
  setFocusedRouteId: (id: string | null) => void;
  focusedDriverId: string | null;
  setFocusedDriverId: (id: string | null) => void;
  mapMode: 'hidden' | 'floating' | 'collapsed';
  setMapMode: (mode: 'hidden' | 'floating' | 'collapsed') => void;
  mapSize: 'S' | 'M' | 'L';
  setMapSize: (size: 'S' | 'M' | 'L') => void;
};

export const useGlobalMapStore = create<GlobalMapState>()(
  persist(
    (set) => ({
      focusedRouteId: null,
      setFocusedRouteId: (id) => set({ focusedRouteId: id, focusedDriverId: null }),
      focusedDriverId: null,
      setFocusedDriverId: (id) => set({ focusedDriverId: id, focusedRouteId: null }),
      mapMode: 'collapsed',
      setMapMode: (mode) => set({ mapMode: mode }),
      mapSize: 'S',
      setMapSize: (size) => set({ mapSize: size }),
    }),
    {
      name: 'asm-global-map-settings',
      partialize: (state) => ({
        mapMode: state.mapMode,
        mapSize: state.mapSize,
      }),
    }
  )
);
