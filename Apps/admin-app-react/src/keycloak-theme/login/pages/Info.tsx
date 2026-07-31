import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "info.ftl" }>, I18n>;

type AnyMsg = (key: string, ...args: string[]) => React.ReactNode;

export default function Info({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { messageHeader, message, requiredActions, skipLink, pageRedirectUri,
          actionUri, client } = kcContext;
  const { msg } = i18n;
  const msgAny = msg as AnyMsg;

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={messageHeader ?? msgAny("infoTitle")}
      displayMessage={false}
    >
      <div className="asm-status-page">
        <div className="asm-status-icon asm-status-icon-green">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M20 6L9 17l-5-5"/>
          </svg>
        </div>

        <p style={{ fontSize: 13, color: "var(--text-secondary)", lineHeight: 1.6, marginBottom: 16, textAlign: "center" }}>
          <span dangerouslySetInnerHTML={{ __html: message.summary }} />
          {requiredActions && (
            <>
              {" "}
              {requiredActions.map((action, idx) => (
                <span key={action}>
                  {msgAny(`requiredAction.${action}`)}
                  {idx < requiredActions.length - 1 ? ", " : ""}
                </span>
              ))}
            </>
          )}
        </p>

        {!skipLink && (pageRedirectUri || actionUri || client?.baseUrl) && (
          <a
            href={pageRedirectUri ?? actionUri ?? client?.baseUrl}
            className="asm-btn-primary"
            style={{ maxWidth: 220 }}
          >
            {actionUri
              ? msg("proceedWithAction")
              : client?.name
                ? msgAny("backTo", client.name)
                : msg("backToApplication")}
          </a>
        )}
      </div>
    </Template>
  );
}
