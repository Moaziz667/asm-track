import { useState, useEffect } from 'react';
import { safeStorage } from '@/lib/storage';

export function useColorScheme() {
  const [colorScheme, setColorSchemeState] = useState<'light' | 'dark'>('light');

  useEffect(() => {
    if (typeof window === 'undefined') return;

    const getScheme = () =>
      (document.documentElement.getAttribute('data-mantine-color-scheme') as 'light' | 'dark') || 'light';

    setColorSchemeState(getScheme());

    const handleMutation = () => {
      setColorSchemeState(getScheme());
    };

    const observer = new MutationObserver(handleMutation);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-mantine-color-scheme'],
    });

    return () => observer.disconnect();
  }, []);

  const setColorScheme = (val: 'light' | 'dark') => {
    if (typeof window === 'undefined') return;
    document.documentElement.setAttribute('data-mantine-color-scheme', val);
    document.documentElement.classList.toggle('dark', val === 'dark');
    safeStorage.setItem('admin-color-scheme', val);
    document.cookie = `asm-theme=${val}; path=/; max-age=31536000; SameSite=Strict`;
  };

  return { colorScheme, setColorScheme };
}
