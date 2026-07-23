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

  // Apply dark/light mode: ?dark= param > asm-theme cookie > OS preference > dark (default)
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const darkParam = params.get("dark");

    let isDark: boolean;
    if (darkParam !== null) {
      isDark = darkParam !== "false";
    } else {
      // Read asm-theme cookie (set by admin app's dark mode toggle)
      const cookieMatch = document.cookie.split("; ").find(c => c.startsWith("asm-theme="));
      const cookieTheme = cookieMatch ? cookieMatch.split("=")[1] : null;

      if (cookieTheme !== null) {
        isDark = cookieTheme !== "light";
      } else {
        // Fall back to OS preference, then dark (matches admin app default)
        isDark = !window.matchMedia?.("(prefers-color-scheme: light)").matches;
      }
    }

    document.documentElement.classList.toggle("dark", isDark);
  }, []);

  // Fix favicon (index.html ships /icon.png which 404s on KC origin)
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
      <main className="asm-form-panel">
        {/* Brand */}
        <div className="asm-brand">
          <img src={logoSrc} alt="ASM Track" className="asm-brand-logo" />
          <span className="asm-brand-name">ASM Track</span>
        </div>

        {/* Card */}
        <div className="asm-card">
          {headerNode && (
            <div className="asm-card-header">
              <h1 className="asm-card-title">{headerNode}</h1>
            </div>
          )}

          {displayMessage && message !== undefined && (message.type !== "warning" || !isAppInitiatedAction) && (
            <AlertBanner type={message.type} html={message.summary} />
          )}

          {children}

          {infoNode !== undefined && infoNode !== null && (
            <div className="asm-card-footer">{infoNode}</div>
          )}
        </div>

        {/* Footer */}
        <div className="asm-footer">
          &copy; {new Date().getFullYear()} ASM Track
        </div>
      </main>
    </div>
  );
}

function AlertBanner({ type, html }: { type: "success" | "warning" | "error" | "info"; html: string }) {
  return (
    <div className={`asm-alert asm-alert-${type}`} role="alert" aria-live="polite" style={{ marginBottom: 16 }}>
      <AlertIcon type={type} />
      <span dangerouslySetInnerHTML={{ __html: html }} />
    </div>
  );
}

function AlertIcon({ type }: { type: string }) {
  const size = 14;
  const stroke = "currentColor";
  const sw = "2.5";
  const props = { width: size, height: size, viewBox: "0 0 24 24", fill: "none", stroke, strokeWidth: sw, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };

  if (type === "error") return (
    <svg {...props}><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
  );
  if (type === "success") return (
    <svg {...props}><path d="M20 6L9 17l-5-5"/></svg>
  );
  if (type === "warning") return (
    <svg {...props}><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
  );
  return (
    <svg {...props}><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
  );
}
