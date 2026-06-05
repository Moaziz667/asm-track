import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "login-oauth-grant.ftl" }>, I18n>;

export default function LoginOauthGrant({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { url, oauth, client } = kcContext;
  const { msg, msgStr } = i18n;

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={msg("oauthGrantTitle")}
    >
      <p style={{ fontSize: 12, color: "var(--text-soft)", marginBottom: 16, lineHeight: 1.6 }}>
        {client.name || client.clientId} {msg("oauthGrantRequest")}
      </p>

      {oauth.clientScopesRequested.length > 0 && (
        <ul className="asm-scope-list">
          {oauth.clientScopesRequested.map((scope, idx) => (
            <li key={idx} className="asm-scope-item">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M20 6L9 17l-5-5"/>
              </svg>
              <span dangerouslySetInnerHTML={{ __html: scope.consentScreenText }} />
            </li>
          ))}
        </ul>
      )}

      <form className="asm-form" action={url.oauthAction} method="post" style={{ marginTop: 8 }}>
        <input type="hidden" name="code" value={oauth.code} />
        <div style={{ display: "flex", gap: 8 }}>
          <button type="submit" name="cancel" className="asm-btn-secondary" style={{ flex: 1 }}>
            {msg("doDecline")}
          </button>
          <button type="submit" name="accept" className="asm-btn-primary" style={{ flex: 2, marginTop: 0 }}>
            {(msgStr as (key: string) => string)("doAllow")}
          </button>
        </div>
      </form>
    </Template>
  );
}
