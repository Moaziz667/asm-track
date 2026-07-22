import { useState } from "react";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "login-otp.ftl" }>, I18n>;

export default function LoginOtp({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { url, otpLogin, messagesPerField } = kcContext;
  const { msg, msgStr } = i18n;
  const [isSubmitting, setIsSubmitting] = useState(false);

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={msg("doLogIn")}
    >
      <p style={{ fontSize: 12.5, color: "var(--text-muted)", marginBottom: 16, lineHeight: 1.6 }}>
        Enter the one-time code from your authenticator app.
      </p>

      <form
        className="asm-form"
        action={url.loginAction}
        method="post"
        onSubmit={() => setIsSubmitting(true)}
      >
        {otpLogin.userOtpCredentials.length > 1 && (
          <div className="asm-field">
            <label className="asm-label">{msg("loginOtpOneTime")}</label>
            <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
              {otpLogin.userOtpCredentials.map((cred, idx) => (
                <label
                  key={cred.id}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 8,
                    padding: "7px 10px",
                    background: "var(--hover-bg)",
                    border: "1px solid var(--border)",
                    borderRadius: "var(--radius)",
                    cursor: "pointer",
                    fontSize: 12.5,
                    color: "var(--text-secondary)",
                  }}
                >
                  <input
                    type="radio"
                    name="selectedCredentialId"
                    value={cred.id}
                    defaultChecked={idx === 0}
                    style={{ accentColor: "var(--brand)" }}
                  />
                  {cred.userLabel}
                </label>
              ))}
            </div>
          </div>
        )}

        <div className="asm-field">
          <label className="asm-label" htmlFor="otp">
            {msg("loginOtpOneTime")}
            <span className="required">*</span>
          </label>
          <div className="asm-input-wrap">
            <input
              id="otp"
              name="otp"
              type="text"
              autoComplete="one-time-code"
              inputMode="numeric"
              pattern="[0-9]*"
              autoFocus
              className={`asm-input${messagesPerField.existsError("totp") ? " error" : ""}`}
              placeholder="000000"
              style={{ letterSpacing: "0.15em", textAlign: "center", fontSize: 15, fontWeight: 600 }}
            />
          </div>
          {messagesPerField.existsError("totp") && (
            <span className="asm-field-error">{messagesPerField.getFirstError("totp")}</span>
          )}
        </div>

        <button type="submit" className="asm-btn-primary" disabled={isSubmitting}>
          {isSubmitting && <span className="asm-spinner" />}
          {isSubmitting ? "Verifying\u2026" : msgStr("doLogIn")}
        </button>
      </form>
    </Template>
  );
}
