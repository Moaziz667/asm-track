import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "login-page-expired.ftl" }>, I18n>;

export default function LoginPageExpired({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { url } = kcContext;
  const { msg } = i18n;

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={msg("pageExpiredTitle")}
    >
      <div className="asm-icon-page">
        <div className="asm-icon-wrap asm-icon-wrap-orange">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10"/>
            <polyline points="12 6 12 12 16 14"/>
          </svg>
        </div>

        <p style={{ fontSize: 13, color: "var(--text-secondary)", lineHeight: 1.6, marginBottom: 24, textAlign: "center" }}>
          {msg("pageExpiredMsg1")}{" "}
          <a href={url.loginRestartFlowUrl} className="asm-link">
            {msg("doClickHere")}
          </a>{" "}
          {msg("pageExpiredMsg2")}{" "}
          <a href={url.loginAction} className="asm-link">
            {msg("doClickHere")}
          </a>
        </p>
      </div>
    </Template>
  );
}
