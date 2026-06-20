import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

const rootElement = document.getElementById('root')!;

// When Keycloak renders a page using this theme, it injects `window.kcContext`.
// In that case we MUST render the Keycloakify login UI — NOT the admin app.
// (Rendering <App/> here loads the admin router on Keycloak's origin and causes
//  an auth redirect loop.)
if (window.kcContext !== undefined) {
  import('./keycloak-theme/kc.gen').then(({ KcPage }) => {
    createRoot(rootElement).render(
      <StrictMode>
        <KcPage kcContext={window.kcContext!} fallback={null} />
      </StrictMode>
    );
  });
} else {
  bootstrapAdminApp();
}

async function bootstrapAdminApp() {
  // Favicon (removed from index.html so it doesn't 404 on Keycloak's origin).
  const favicon = document.createElement('link');
  favicon.rel = 'icon';
  favicon.type = 'image/png';
  favicon.href = '/icon.png';
  document.head.appendChild(favicon);

  // Dynamic imports keep the admin app out of the Keycloak theme bundle.
  await import('./globals.css');
  const { default: App } = await import('./App.tsx');
  const { safeStorage } = await import('./lib/storage');

  // Accessibility audit — DEV ONLY. axe scans the live DOM and logs WCAG violations to the
  // console as you navigate, so a11y regressions surface immediately. import.meta.env.DEV is
  // false in production builds, so this whole block (and the dep) is tree-shaken out.
  if (import.meta.env.DEV) {
    try {
      const [{ default: axe }, React, ReactDOM] = await Promise.all([
        import('@axe-core/react'),
        import('react'),
        import('react-dom'),
      ]);
      void axe(React, ReactDOM, 1000);
    } catch { /* axe is optional tooling — never block the app */ }
  }

  // One-time kill-switch for any stale service worker on this origin.
  if ('serviceWorker' in navigator) {
    const regs = await navigator.serviceWorker.getRegistrations();
    if (regs.length > 0) {
      await Promise.all(regs.map((r) => r.unregister()));
      if (window.caches) {
        const keys = await caches.keys();
        await Promise.all(keys.map((k) => caches.delete(k)));
      }
      window.location.reload();
      return;
    }
  }

  // Synchronous theme apply to avoid flashes
  const theme = safeStorage.getItem('admin-color-scheme') || 'dark';
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
}
