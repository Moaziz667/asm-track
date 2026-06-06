import { Suspense, lazy } from "react";
import type { KcContext } from "./KcContext";
import { useI18n } from "./i18n";
import DefaultPage from "keycloakify/login/DefaultPage";
import Template from "./Template";

const Login               = lazy(() => import("./pages/Login"));
const LoginResetPassword  = lazy(() => import("./pages/LoginResetPassword"));
const LoginUpdatePassword = lazy(() => import("./pages/LoginUpdatePassword"));
const LoginVerifyEmail    = lazy(() => import("./pages/LoginVerifyEmail"));
const LoginPageExpired    = lazy(() => import("./pages/LoginPageExpired"));
const LoginOtp            = lazy(() => import("./pages/LoginOtp"));
const LoginConfigTotp     = lazy(() => import("./pages/LoginConfigTotp"));
const LoginOauthGrant     = lazy(() => import("./pages/LoginOauthGrant"));
const ErrorPage           = lazy(() => import("./pages/Error"));
const Info                = lazy(() => import("./pages/Info"));

const UserProfileFormFields = lazy(
  () => import("keycloakify/login/UserProfileFormFields")
);

export default function KcPage(props: { kcContext: KcContext }) {
  const { kcContext } = props;
  const { i18n } = useI18n({ kcContext });

  return (
    <Suspense>
      {(() => {
        // Each case passes kcContext directly so TypeScript narrows the union correctly
        switch (kcContext.pageId) {
          case "login.ftl":
            return <Login kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          case "login-reset-password.ftl":
            return <LoginResetPassword kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          case "login-update-password.ftl":
            return <LoginUpdatePassword kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          case "login-verify-email.ftl":
            return <LoginVerifyEmail kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          case "login-page-expired.ftl":
            return <LoginPageExpired kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          case "login-otp.ftl":
            return <LoginOtp kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          case "login-config-totp.ftl":
            return <LoginConfigTotp kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          case "login-oauth-grant.ftl":
            return <LoginOauthGrant kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          case "error.ftl":
            return <ErrorPage kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          case "info.ftl":
            return <Info kcContext={kcContext} i18n={i18n} Template={Template} doUseDefaultCss={false} />;
          default:
            return (
              <DefaultPage
                kcContext={kcContext}
                i18n={i18n}
                Template={Template}
                doUseDefaultCss={true}
                doMakeUserConfirmPassword={false}
                UserProfileFormFields={UserProfileFormFields}
              />
            );
        }
      })()}
    </Suspense>
  );
}
