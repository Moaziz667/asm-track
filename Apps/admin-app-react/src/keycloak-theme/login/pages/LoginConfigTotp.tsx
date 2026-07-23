import { useState } from "react";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "login-config-totp.ftl" }>, I18n>;

export default function LoginConfigTotp({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { url, totp, isAppInitiatedAction, messagesPerField } = kcContext;
  const { msg, msgStr } = i18n;
  const [isSubmitting, setIsSubmitting] = useState(false);

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={msg("loginTotpTitle")}
    >
      {/* Step 1 */}
      <div style={{ marginBottom: 20 }}>
        <p className="asm-step-label">Step 1 &mdash; Install an authenticator app</p>
        <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
          {totp.supportedApplications.map(app => (
            <span key={app} style={{
              fontSize: 11.5,
              fontWeight: 500,
              color: "var(--text-secondary)",
              background: "var(--hover-bg)",
              border: "1px solid var(--border)",
              borderRadius: "var(--radius)",
              padding: "3px 8px",
            }}>
              {app}
            </span>
          ))}
        </div>
      </div>

      {/* Step 2 */}
      <div style={{ marginBottom: 20 }}>
        <p className="asm-step-label">Step 2 &mdash; Scan QR code</p>
        <div className="asm-qr-wrap">
          <img src={`data:image/png;base64,${totp.totpSecretQrCode}`} alt="QR code" />
        </div>
        <p style={{ fontSize: 11.5, color: "var(--text-soft)", textAlign: "center", marginTop: 8 }}>
          {msg("loginTotpUnableToScan")}{" "}
          <a href={totp.manualUrl} className="asm-link" style={{ fontSize: 11.5 }}>
            {(msg as (key: string) => React.ReactNode)("loginTotpManualMode")}
          </a>
        </p>
        <div className="asm-secret-key" style={{ marginTop: 8 }}>
          {totp.totpSecretEncoded}
        </div>
      </div>

      {/* Step 3 */}
      <div>
        <p className="asm-step-label">Step 3 &mdash; Verify</p>
        <form
          className="asm-form"
          action={url.loginAction}
          method="post"
          onSubmit={() => setIsSubmitting(true)}
        >
          <input type="hidden" name="totpSecret" value={totp.totpSecret} />

          <div className="asm-field">
            <label className="asm-label" htmlFor="totp">
              {msg("authenticatorCode")}
              <span className="required">*</span>
            </label>
            <input
              id="totp"
              name="totp"
              type="text"
              autoComplete="one-time-code"
              inputMode="numeric"
              pattern="[0-9]*"
              autoFocus
              className={`asm-input${messagesPerField.existsError("totp") ? " error" : ""}`}
              placeholder="000000"
              style={{ textAlign: "center", letterSpacing: "0.12em", fontSize: 15, fontWeight: 600 }}
            />
            {messagesPerField.existsError("totp") && (
              <span className="asm-field-error">{messagesPerField.getFirstError("totp")}</span>
            )}
          </div>

          <div className="asm-field">
            <label className="asm-label" htmlFor="userLabel">
              {msg("loginTotpDeviceName")}
              {totp.otpCredentials.length >= 1 && <span className="required">*</span>}
            </label>
            <input
              id="userLabel"
              name="userLabel"
              type="text"
              className={`asm-input${messagesPerField.existsError("userLabel") ? " error" : ""}`}
              placeholder="My phone"
            />
          </div>

          <div style={{ display: "flex", gap: 8 }}>
            {isAppInitiatedAction && (
              <button type="submit" name="cancel-aia" value="true" className="asm-btn-secondary" style={{ flex: 1 }}>
                {msg("doCancel")}
              </button>
            )}
            <button type="submit" className="asm-btn-primary" disabled={isSubmitting} style={{ flex: 2, marginTop: 0 }}>
              {isSubmitting && <span className="asm-spinner" />}
              {isSubmitting ? "Saving\u2026" : msgStr("doSubmit")}
            </button>
          </div>
        </form>
      </div>
    </Template>
  );
}
