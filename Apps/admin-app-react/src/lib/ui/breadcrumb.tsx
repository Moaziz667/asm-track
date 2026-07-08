
import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from 'react';

export type BreadcrumbItem = {
  label: string;
  href?: string;
};

type ContextValue = {
  trail: BreadcrumbItem[];
  setTrail: (items: BreadcrumbItem[]) => void;
  clearTrail: () => void;
};

const BreadcrumbContext = createContext<ContextValue>({
  trail: [],
  setTrail: () => {},
  clearTrail: () => {},
});

export function BreadcrumbProvider({ children }: { children: ReactNode }) {
  const [trail, setTrailState] = useState<BreadcrumbItem[]>([]);
  const setTrail = useCallback((items: BreadcrumbItem[]) => setTrailState(items), []);
  const clearTrail = useCallback(() => setTrailState([]), []);
  return (
    <BreadcrumbContext.Provider value={{ trail, setTrail, clearTrail }}>
      {children}
    </BreadcrumbContext.Provider>
  );
}

export function useBreadcrumb() {
  return useContext(BreadcrumbContext);
}

/** Call at the top of a page component to push a custom trail. Clears on unmount. */
export function usePageBreadcrumb(items: BreadcrumbItem[]) {
  const { setTrail, clearTrail } = useBreadcrumb();
  const key = items.map(i => `${i.label}|${i.href ?? ''}`).join('~');
  useEffect(() => {
    if (items.length > 0) setTrail(items);
    return clearTrail;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
}

