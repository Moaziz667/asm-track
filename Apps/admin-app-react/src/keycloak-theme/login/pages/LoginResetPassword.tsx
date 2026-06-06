import { useState } from "react";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

type Props = PageProps<Extract<KcContext, { pageId: "login-reset-password.ftl" }>, I18n>;

export default function LoginResetPassword({ kcContext, i18n, Template, doUseDefaultCss }: Props) {
  const { url, realm, auth, messagesPerField } = kcContext;
  const { msg, msgStr } = i18n;
  const [isSubmitting, setIsSubmitting] = useState(false);

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      headerNode={msg("emailForgotTitle")}
      infoNode={
        <a href={url.loginUrl} className="asm-link">
          ← {msg("backToLogin")}
        </a>
      }
    >
      <p style={{ fontSize: 12, color: "var(--text-soft)", marginBottom: 20, lineHeight: 1.6 }}>
        {realm.duplicateEmailsAllowed
          ? msg("emailInstructionUsername")
          : msg("emailInstruction")}
      </p>

      <form
        className="asm-form"
        action={url.loginAction}
        method="post"
        onSubmit={() => setIsSubmitting(true)}
      >
        <div className="asm-field">
          <label className="asm-label" htmlFor="username">
            {!realm.loginWithEmailAllowed
              ? msg("username")
              : !realm.registrationEmailAsUsername
                ? msg("usernameOrEmail")
                : msg("email")}
            <span className="required">*</span>
          </label>
          <div className="asm-input-wrap">
            <input
              id="username"
              name="username"
              type="text"
              autoFocus
              defaultValue={auth?.attemptedUsername ?? ""}
              className={`asm-input${messagesPerField.existsError("username") ? " error" : ""}`}
              placeholder={realm.registrationEmailAsUsername ? "you@company.com" : msgStr("username")}
            />
          </div>
          {messagesPerField.existsError("username") && (
            <span className="asm-field-error">{messagesPerField.getFirstError("username")}</span>
          )}
        </div>

        <button type="submit" className="asm-btn-primary" disabled={isSubmitting}>
          {isSubmitting ? <span className="asm-spinner" /> : null}
          {isSubmitting ? "Sending…" : msgStr("doSubmit")}
        </button>
      </form>
    </Template>
  );
}
