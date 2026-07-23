import { useState } from "react";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "login-update-password.ftl" }>, I18n>;

export default function LoginUpdatePassword({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { url, messagesPerField, isAppInitiatedAction } = kcContext;
  const { msg, msgStr } = i18n;
  const [showNew, setShowNew] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={msg("updatePasswordTitle")}
    >
      <form
        className="asm-form"
        action={url.loginAction}
        method="post"
        onSubmit={() => setIsSubmitting(true)}
      >
        <input type="text" hidden autoComplete="username" />

        <div className="asm-field">
          <label className="asm-label" htmlFor="password-new">
            {msg("passwordNew")}
            <span className="required">*</span>
          </label>
          <div className="asm-input-wrap">
            <input
              id="password-new"
              name="password-new"
              type={showNew ? "text" : "password"}
              autoFocus
              autoComplete="new-password"
              className={`asm-input asm-input-with-toggle${messagesPerField.existsError("password", "password-confirm") ? " error" : ""}`}
            />
            <button type="button" className="asm-toggle-btn" onClick={() => setShowNew(v => !v)} tabIndex={-1}>
              {showNew ? <EyeOffIcon /> : <EyeIcon />}
            </button>
          </div>
          {messagesPerField.existsError("password") && (
            <span className="asm-field-error">{messagesPerField.getFirstError("password")}</span>
          )}
        </div>

        <div className="asm-field">
          <label className="asm-label" htmlFor="password-confirm">
            {msg("passwordConfirm")}
            <span className="required">*</span>
          </label>
          <div className="asm-input-wrap">
            <input
              id="password-confirm"
              name="password-confirm"
              type={showConfirm ? "text" : "password"}
              autoComplete="new-password"
              className={`asm-input asm-input-with-toggle${messagesPerField.existsError("password-confirm") ? " error" : ""}`}
            />
            <button type="button" className="asm-toggle-btn" onClick={() => setShowConfirm(v => !v)} tabIndex={-1}>
              {showConfirm ? <EyeOffIcon /> : <EyeIcon />}
            </button>
          </div>
          {messagesPerField.existsError("password-confirm") && (
            <span className="asm-field-error">{messagesPerField.getFirstError("password-confirm")}</span>
          )}
        </div>

        {isAppInitiatedAction ? (
          <div style={{ display: "flex", gap: 8 }}>
            <button type="submit" name="cancel-aia" value="true" className="asm-btn-secondary" style={{ flex: 1 }}>
              {msg("doCancel")}
            </button>
            <button type="submit" className="asm-btn-primary" disabled={isSubmitting} style={{ flex: 2, marginTop: 0 }}>
              {isSubmitting && <span className="asm-spinner" />}
              {msgStr("doSubmit")}
            </button>
          </div>
        ) : (
          <button type="submit" className="asm-btn-primary" disabled={isSubmitting}>
            {isSubmitting && <span className="asm-spinner" />}
            {isSubmitting ? "Saving\u2026" : msgStr("doSubmit")}
          </button>
        )}
      </form>
    </Template>
  );
}

function EyeIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/>
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
