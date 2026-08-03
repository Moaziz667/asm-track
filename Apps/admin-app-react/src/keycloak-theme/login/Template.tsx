import { useEffect } from "react";
import type { TemplateProps } from "keycloakify/login/TemplateProps";
import type { KcContext } from "./KcContext";
import type { I18n } from "./i18n";
import "./theme.css";

export default function Template({
  kcContext,
  i18n,
  children,
  displayMessage = true,
  headerNode,
  infoNode,
}: TemplateProps<KcContext, I18n>) {
  const { message, isAppInitiatedAction, url } = kcContext;
  const { msgStr } = i18n;
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
      {/* ── Context panel ────────────────────────────────────────────────────────
          Sign-in pages are reached from an email link as often as from a bookmark, so the
          first job of this screen is to say which system is asking for a password. The panel
          answers that with what the product does, in the operator's own vocabulary — routes,
          drivers, proof of delivery — rather than the "Secure & Reliable / Collaboration"
          filler that fits any product and therefore identifies none.

          No icon tile above each heading: PRODUCT.md names that as an anti-reference, and it
          would turn an operations console into a marketing page. The panel is hidden below
          960px, where the form is the only thing that matters. */}
      <aside className="asm-context" aria-hidden="true">
        <div className="asm-brand">
          <img src={logoSrc} alt="" className="asm-brand-logo" />
          <span className="asm-brand-name">ASM Track</span>
        </div>

        <h2 className="asm-context-title">{msgStr("asmTagline")}</h2>
        <p className="asm-context-intro">{msgStr("asmIntro")}</p>

        <ul className="asm-context-list">
          <li>
            <span className="asm-context-icon"><RouteIcon /></span>
            <span className="asm-context-point">{msgStr("asmPointPlan")}</span>
            <span className="asm-context-sub">{msgStr("asmPointPlanSub")}</span>
          </li>
          <li>
            <span className="asm-context-icon"><PulseIcon /></span>
            <span className="asm-context-point">{msgStr("asmPointTrack")}</span>
            <span className="asm-context-sub">{msgStr("asmPointTrackSub")}</span>
          </li>
          <li>
            <span className="asm-context-icon"><ProofIcon /></span>
            <span className="asm-context-point">{msgStr("asmPointProof")}</span>
            <span className="asm-context-sub">{msgStr("asmPointProofSub")}</span>
          </li>
        </ul>
      </aside>

      <main className="asm-form-panel">
        {/* Brand — carried here too, for the narrow layout where the panel is gone. */}
        <div className="asm-brand asm-brand-compact">
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
          <span className="asm-footer-secure">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="11" width="18" height="11" rx="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" />
            </svg>
            {msgStr("asmSecure")}
          </span>
          <span aria-hidden="true">·</span>
          <span>&copy; {new Date().getFullYear()} ASM Track</span>
        </div>
      </main>
    </div>
  );
}

/* Inline rather than an icon package: the theme ships as a JAR Keycloak serves, and three
   glyphs are not worth pulling a dependency into it. Each one names its own point — a route
   with stops, a live signal, a signed document — so none of them is interchangeable filler. */
const svg = {
  width: 16, height: 16, viewBox: "0 0 24 24", fill: "none",
  stroke: "currentColor", strokeWidth: 1.8,
  strokeLinecap: "round" as const, strokeLinejoin: "round" as const,
};

function RouteIcon() {
  return (
    <svg {...svg} aria-hidden="true">
      <circle cx="6" cy="19" r="2" /><circle cx="18" cy="5" r="2" />
      <path d="M8 19h5a3 3 0 0 0 0-6h-2a3 3 0 0 1 0-6h5" />
    </svg>
  );
}

function PulseIcon() {
  return (
    <svg {...svg} aria-hidden="true">
      <path d="M3 12h4l2.5-7 4 14L16 12h5" />
    </svg>
  );
}

function ProofIcon() {
  return (
    <svg {...svg} aria-hidden="true">
      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
      <path d="M14 3v5h5" /><path d="M9 15c1.5-2 2.5 1 4-1" />
    </svg>
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
