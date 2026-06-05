import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "error.ftl" }>, I18n>;

export default function Error({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { message, client } = kcContext;
  const { msg } = i18n;

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={msg("errorTitle")}
      displayMessage={false}
    >
      <div className="asm-icon-page">
        <div className="asm-icon-wrap asm-icon-wrap-red">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
        </div>

        <div
          className="asm-alert asm-alert-error"
          style={{ textAlign: "left", marginBottom: 20 }}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0, marginTop: 1 }}>
            <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <span dangerouslySetInnerHTML={{ __html: message.summary }} />
        </div>

        {client?.baseUrl && (
          <a href={client.baseUrl} className="asm-btn-secondary" style={{ display: "inline-flex", maxWidth: 200, margin: "0 auto" }}>
            ← Back to application
          </a>
        )}
      </div>
    </Template>
  );
}
