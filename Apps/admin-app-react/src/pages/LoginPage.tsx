import { useState, useEffect, useRef } from 'react';
import { IconAlertCircle } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { useAuth } from 'react-oidc-context';

/**
 * Entry / fallback screen. On load it redirects straight to Keycloak; this card only shows briefly,
 * or as the retry surface when Keycloak is unreachable. Deliberately theme-independent — a single fixed
 * neutral palette (no dark/light), so it renders identically whatever the app's color scheme is.
 */
export default function LoginPage() {
  const t = useT();
  const auth = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const triggered = useRef(false);

  const startLogin = () => {
    setLoading(true);
    setError('');
    auth.signinRedirect().catch((err) => {
      setError(err.message);
      setLoading(false);
      triggered.current = false;
    });
  };

  // Keycloak is the first page: redirect straight there on load.
  useEffect(() => {
    if (triggered.current) return;
    if (auth.isLoading || auth.activeNavigator) return;
    if (auth.isAuthenticated || auth.error) return;
    triggered.current = true;
    startLogin();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth.isLoading, auth.activeNavigator, auth.isAuthenticated, auth.error]);

  const displayError = error || auth.error?.message || '';

  return (
    <div style={S.page}>
      <main style={S.card}>
        <div style={S.brand}>
          <img src="/icon.png" alt="" width={32} height={32} style={{ borderRadius: 7 }} />
          <span style={S.brandName}>ASM Track</span>
        </div>

        <h1 style={S.title}>{t.loginPage.welcomeBack}</h1>
        <p style={S.subtitle}>{t.loginPage.signInToContinue}</p>

        {displayError && (
          <div style={S.error} role="alert">
            <IconAlertCircle size={15} style={{ flexShrink: 0 }} />
            <span>{displayError}</span>
          </div>
        )}

        <button
          type="button"
          onClick={startLogin}
          disabled={loading}
          style={{ ...S.button, ...(loading ? S.buttonLoading : null) }}
          onMouseEnter={(e) => { if (!loading) e.currentTarget.style.background = BTN_HOVER; }}
          onMouseLeave={(e) => { if (!loading) e.currentTarget.style.background = BTN; }}
        >
          {loading ? (
            <>
              <svg className="animate-spin" width="15" height="15" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" strokeOpacity="0.3" />
                <path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
              </svg>
              {t.loginPage.buttonLoading}
            </>
          ) : (
            t.loginPage.button
          )}
        </button>

        <p style={S.help}>
          {t.loginPage.needAccess}{' '}
          <a href="mailto:contact@asmtrack.com" style={S.link}>{t.loginPage.contactTeam}</a>
        </p>
      </main>
    </div>
  );
}

// Fixed neutral palette — intentionally NOT tied to the app's dark/light tokens.
const INK = '#1A2230';
const MUTED = '#5B6472';
const BORDER = '#E4E7EC';
const BTN = '#3E6AE1';
const BTN_HOVER = '#2B52C3';

const S: Record<string, React.CSSProperties> = {
  page: {
    minHeight: '100dvh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    background: '#F6F7F9',
    fontFamily: 'var(--font-sans, "Open Sans", system-ui, sans-serif)',
  },
  card: {
    width: '100%',
    maxWidth: 360,
    background: '#FFFFFF',
    border: `1px solid ${BORDER}`,
    borderRadius: 12,
    padding: '36px 32px',
    textAlign: 'center',
  },
  brand: { display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10, marginBottom: 28 },
  brandName: { fontSize: 16, fontWeight: 700, color: INK, letterSpacing: '-0.2px' },
  title: { fontSize: 20, fontWeight: 700, color: INK, margin: 0, letterSpacing: '-0.2px' },
  subtitle: { fontSize: 13.5, color: MUTED, margin: '6px 0 24px', lineHeight: 1.5 },
  error: {
    display: 'flex', alignItems: 'center', gap: 8, textAlign: 'left',
    padding: '10px 12px', marginBottom: 18, borderRadius: 8,
    background: '#FEF3F2', border: '1px solid #FDA29B', color: '#B42318',
    fontSize: 12.5, fontWeight: 500,
  },
  button: {
    width: '100%', height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
    background: BTN, color: '#FFFFFF', fontSize: 14, fontWeight: 600,
    border: 'none', borderRadius: 8, cursor: 'pointer', fontFamily: 'inherit',
    transition: 'background 0.15s',
  },
  buttonLoading: { background: '#9DB2E9', cursor: 'default' },
  help: { fontSize: 12.5, color: MUTED, margin: '22px 0 0' },
  link: { color: BTN, fontWeight: 600, textDecoration: 'none' },
};
