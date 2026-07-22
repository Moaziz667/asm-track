import { useState } from "react";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "login.ftl" }>, I18n>;

export default function Login({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { url, realm, usernameHidden, login, messagesPerField, social } = kcContext;
  const { msg, msgStr } = i18n;

  const [showPassword, setShowPassword] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const usernameLabel = !realm.loginWithEmailAllowed
    ? msg("username")
    : !realm.registrationEmailAsUsername
      ? msg("usernameOrEmail")
      : msg("email");

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={msgStr("loginAccountTitle")}
      displayMessage={!messagesPerField.existsError("username", "password")}
    >
      <form
        className="asm-form"
        action={url.loginAction}
        method="post"
        onSubmit={() => setIsSubmitting(true)}
      >
        {!usernameHidden && (
          <div className="asm-field">
            <label className="asm-label" htmlFor="username">
              {usernameLabel}
              <span className="required">*</span>
            </label>
            <div className="asm-input-wrap">
              <input
                id="username"
                name="username"
                type={realm.registrationEmailAsUsername ? "email" : "text"}
                autoComplete="username"
                autoFocus
                defaultValue={login.username ?? ""}
                className={`asm-input${messagesPerField.existsError("username", "password") ? " error" : ""}`}
                placeholder={realm.registrationEmailAsUsername ? "you@company.com" : msgStr("username")}
              />
            </div>
            {messagesPerField.existsError("username") && (
              <span className="asm-field-error">{messagesPerField.getFirstError("username")}</span>
            )}
          </div>
        )}

        {realm.password && (
          <div className="asm-field">
            <label className="asm-label" htmlFor="password">
              {msg("password")}
              <span className="required">*</span>
            </label>
            <div className="asm-input-wrap">
              <input
                id="password"
                name="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                className={`asm-input asm-input-with-toggle${messagesPerField.existsError("username", "password") ? " error" : ""}`}
              />
              <button
                type="button"
                className="asm-toggle-btn"
                onClick={() => setShowPassword(v => !v)}
                tabIndex={-1}
                aria-label={showPassword ? "Hide password" : "Show password"}
              >
                {showPassword ? <EyeOffIcon /> : <EyeIcon />}
              </button>
            </div>
            {messagesPerField.existsError("password") && (
              <span className="asm-field-error">{messagesPerField.getFirstError("password")}</span>
            )}
          </div>
        )}

        <div className="asm-row">
          {realm.rememberMe && !usernameHidden && (
            <label className="asm-checkbox-label">
              <input
                type="checkbox"
                name="rememberMe"
                defaultChecked={!!login.rememberMe}
              />
              {msg("rememberMe")}
            </label>
          )}
          {realm.resetPasswordAllowed && (
            <a href={url.loginResetCredentialsUrl} className="asm-link">
              {msg("doForgotPassword")}
            </a>
          )}
        </div>

        <input type="hidden" name="credentialId" value={kcContext.auth?.selectedCredential ?? ""} />

        <button type="submit" className="asm-btn-primary" disabled={isSubmitting}>
          {isSubmitting && <span className="asm-spinner" />}
          {isSubmitting ? "Signing in\u2026" : msgStr("doLogIn")}
        </button>
      </form>

      {social?.providers && social.providers.length > 0 && (
        <>
          <div className="asm-divider" style={{ marginTop: 20 }}>
            <span className="asm-divider-line" />
            <span className="asm-divider-text">{msg("identity-provider-login-label")}</span>
            <span className="asm-divider-line" />
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 12 }}>
            {social.providers.map(p => (
              <a key={p.providerId} href={p.loginUrl} className="asm-btn-secondary">
                {p.iconClasses && <span className={p.iconClasses} aria-hidden />}
                {p.displayName}
              </a>
            ))}
          </div>
        </>
      )}

      {realm.registrationAllowed && !kcContext.registrationDisabled && (
        <p className="asm-card-footer" style={{ marginTop: 16 }}>
          {msg("noAccount")}{" "}
          <a href={url.registrationUrl} className="asm-link">
            {msg("doRegister")}
          </a>
        </p>
      )}
    </Template>
  );
}

function EyeIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7z"/>
      <circle cx="12" cy="12" r="3"/>
    </svg>
  );
}

function EyeOffIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
      <line x1="1" y1="1" x2="23" y2="23"/>
    </svg>
  );
}
