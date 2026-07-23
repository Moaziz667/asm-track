import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "login-verify-email.ftl" }>, I18n>;

export default function LoginVerifyEmail({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { url, user } = kcContext;
  const { msg } = i18n;

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={msg("emailVerifyTitle")}
    >
      <div className="asm-status-page">
        <div className="asm-status-icon asm-status-icon-blue">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"/>
            <polyline points="22,6 12,13 2,6"/>
          </svg>
        </div>

        <p style={{ fontSize: 13, color: "var(--text-secondary)", lineHeight: 1.6, marginBottom: 16 }}>
          {msg("emailVerifyInstruction1", user?.email ?? "")}
        </p>

        <p style={{ fontSize: 12.5, color: "var(--text-muted)", lineHeight: 1.6 }}>
          {msg("emailVerifyInstruction2")}{" "}
          <a href={url.loginAction} className="asm-link">
            {msg("doClickHere")}
          </a>{" "}
          {msg("emailVerifyInstruction3")}
        </p>
      </div>
    </Template>
  );
}
