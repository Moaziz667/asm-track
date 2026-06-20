import { useEffect, type ReactNode } from "react";
import type { TemplateProps } from "keycloakify/login/TemplateProps";
import type { KcContext } from "./KcContext";
import type { I18n } from "./i18n";
import "./theme.css";

export default function Template({
  kcContext,
  i18n,
  doUseDefaultCss,
  children,
  displayMessage = true,
  headerNode,
  infoNode,
}: TemplateProps<KcContext, I18n>) {
  const { message, isAppInitiatedAction, url } = kcContext;
  const logoSrc = `${url.resourcesPath}/dist/icon.png`;

  // index.html ships an absolute `<link rel="icon" href="/icon.png">` which 404s
  // on Keycloak's origin. Repoint the favicon at the theme resource path.
  useEffect(() => {
    let link = document.querySelector<HTMLLinkElement>("link[rel='icon']");
    if (!link) {
      link = document.createElement("link");
      link.rel = "icon";
      document.head.appendChild(link);
    }
    link.href = logoSrc;
  }, [logoSrc]);

  return (
    <div className="asm-layout">
      {/* ── Left brand panel ───────────────────────────────────────────── */}
      <aside className="asm-brand-panel">
        <div className="asm-brand-logo">
          <img src={logoSrc} alt="ASM Track" />
          <div className="asm-brand-logo-text">
            <span className="asm-brand-name">ASM Track</span>
            <span className="asm-brand-tagline">Planifier. Suivre. Livrer.</span>
          </div>
        </div>

        <div className="asm-brand-body">
          <h2 className="asm-brand-headline">
            La console de pilotage de vos <span>livraisons</span>.
          </h2>
          <p className="asm-brand-description">
            Planification des tournées, suivi en temps réel et preuve de livraison — réunis dans une seule plateforme.
          </p>
          <div className="asm-trust">
            <div className="asm-trust-item">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
              Accès chiffré &amp; contrôlé par rôle
            </div>
            <div className="asm-trust-item">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
              Suivi opérationnel en temps réel
            </div>
            <div className="asm-trust-item">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              Synchronisation ERP sécurisée
            </div>
          </div>
        </div>

        <div className="asm-brand-footer">
          <span className="asm-brand-footer-text">© {new Date().getFullYear()} ASM Track · Planifier. Suivre. Livrer.</span>
        </div>
      </aside>

      {/* ── Right form panel ───────────────────────────────────────────── */}
      <main className="asm-form-panel">
        {/* Mobile logo */}
        <div className="asm-mobile-logo">
          <img src={logoSrc} alt="ASM Track" />
          <span className="asm-mobile-logo-name">ASM Track</span>
        </div>

        <div className="asm-card">
          {/* Page header */}
          {headerNode && (
            <div className="asm-card-header">
              <h1 className="asm-card-title">{headerNode}</h1>
            </div>
          )}

          {/* Global alert messages */}
          {displayMessage && message !== undefined && (message.type !== "warning" || !isAppInitiatedAction) && (
            <AlertBanner type={message.type} html={message.summary} />
          )}

          {/* Page content */}
          {children}

          {/* Info node (e.g. "back to login" links) */}
          {infoNode !== undefined && infoNode !== null && (
            <div className="asm-card-footer">{infoNode}</div>
          )}
        </div>
      </main>
    </div>
  );
}

function AlertBanner({ type, html }: { type: "success" | "warning" | "error" | "info"; html: string }) {
  const cls = `asm-alert asm-alert-${type}`;
  return (
    <div className={cls} role="alert" aria-live="polite" style={{ marginBottom: 16 }}>
      <AlertIcon type={type} />
      <span dangerouslySetInnerHTML={{ __html: html }} />
    </div>
  );
}

function AlertIcon({ type }: { type: string }) {
  if (type === "error") return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
    </svg>
  );
  if (type === "success") return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6L9 17l-5-5"/>
    </svg>
  );
  if (type === "warning") return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
    </svg>
  );
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
    </svg>
  );
}
