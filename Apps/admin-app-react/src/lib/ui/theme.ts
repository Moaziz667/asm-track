import { useState, useEffect } from 'react';

/**
 * A hook that tracks whether dark mode is currently active by listening to
 * storage events and observing mutations on the `document.documentElement` class list.
 */
export function useIsDark(): boolean {
  const [isDark, setIsDark] = useState(() => {
    if (typeof window === 'undefined') return false;
    return document.documentElement.classList.contains('dark');
  });

  useEffect(() => {
    const checkDark = () => {
      setIsDark(document.documentElement.classList.contains('dark'));
    };

    // Check initially
    checkDark();

    // 1. Listen to storage changes (for cross-tab synchronization)
    const handleStorage = (e: StorageEvent) => {
      if (e.key === 'admin-color-scheme') {
        checkDark();
      }
    };

    // 2. Observe mutations on documentElement's class list
    const observer = new MutationObserver(() => {
      checkDark();
    });

    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class'],
    });

    window.addEventListener('storage', handleStorage);

    return () => {
      observer.disconnect();
      window.removeEventListener('storage', handleStorage);
    };
  }, []);

  return isDark;
}