import { useRef } from 'react';
import { useAuth } from 'react-oidc-context';
import { useRealtimeEvent } from './RealtimeProvider';
import { safeStorage } from '@/lib/storage';
import { showErrorToast } from '@/lib/toast-service';
import { useT } from '@/lib/LocaleContext';

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

    const target = String(evt.payload?.email ?? '').trim().toLowerCase();
    const mine = String(auth.user?.profile?.email ?? safeStorage.getItem('admin_email') ?? '')
      .trim()
      .toLowerCase();
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
