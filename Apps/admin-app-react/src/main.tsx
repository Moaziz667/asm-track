import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.tsx';
import './globals.css'; // Next.js globals.css
import { safeStorage } from './lib/storage';

const rootElement = document.getElementById('root')!;

// Synchronous theme apply to avoid flashes
const theme = safeStorage.getItem('admin-color-scheme') || 'light';
document.documentElement.classList.toggle('dark', theme === 'dark');
document.documentElement.setAttribute('data-mantine-color-scheme', theme);

// Apply Locale — read cookie first (where setLocale writes), fallback to localStorage
const localeMatch = document.cookie.match(/(?:^|;\s*)asm-locale=([^;]+)/);
const locale = localeMatch?.[1] || safeStorage.getItem('asm-locale') || 'fr';
document.documentElement.setAttribute('data-locale', locale);
document.documentElement.lang = locale;
document.documentElement.dir = locale === 'ar' ? 'rtl' : 'ltr';

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

