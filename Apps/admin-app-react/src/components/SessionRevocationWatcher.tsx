import { useRef } from 'react';
import { useAuth } from 'react-oidc-context';
import { useRealtimeEvent } from './RealtimeProvider';
import { showErrorToast } from '@/lib/ui/toast-service';
import { useT } from '@/lib/i18n/LocaleContext';

/**
 * S2 of the force-logout fix: listens for a `session.revoked` realtime event and, if it targets the
 * current user, logs them out instantly instead of waiting for the access token to expire (S1 is the
 * backstop for missed events / offline clients). Renders nothing.
 */
export default function SessionRevocationWatcher() {
  const auth = useAuth();
  const t = useT();
  const triggered = useRef(false);

  useRealtimeEvent(['session.revoked'], (evt) => {
    if (triggered.current) return;

    // Match on the immutable OIDC subject (sub) — stable across email/username changes and what the
    // Keycloak back-channel-logout token carries. Always present in our tokens (scope=openid).
    const target = String(evt.payload?.sub ?? '').trim();
    const mine = String(auth.user?.profile?.sub ?? '').trim();
    if (!target || !mine || target !== mine) return;

    triggered.current = true;
    showErrorToast(null, t.topNav.sessionRevoked);
    // Clear local session immediately, then end the Keycloak session. The KC session is already
    // revoked server-side, so signoutRedirect may fail — fall back to a hard redirect to login.
    void (async () => {
      try {
        await auth.removeUser();
        await auth.signoutRedirect();
      } catch {
        window.location.href = '/login';
      }
    })();
  });

  return null;
}
