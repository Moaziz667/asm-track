import { useState, useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { IconAlertCircle, IconSun, IconMoon } from '@tabler/icons-react';
import { useT, useLocaleContext } from '@/lib/i18n/LocaleContext';
import LanguageSelector from '@/components/LanguageSelector';
import { useAuth } from 'react-oidc-context';
import { safeStorage } from '@/lib/storage';

export default function LoginPage() {
  const t = useT();
  const { locale } = useLocaleContext();
  const auth = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const triggered = useRef(false);

  const startLogin = () => {
    setLoading(true);
    auth.signinRedirect().catch((err) => {
      setError(err.message);
      setLoading(false);
      triggered.current = false;
    });
  };

  // Keycloak is the first page: redirect straight there on load. The branded
  // card below only shows briefly (or as an error/retry fallback).
  useEffect(() => {
    if (triggered.current) return;
    if (auth.isLoading || auth.activeNavigator) return;
    if (auth.isAuthenticated || auth.error) return;
    triggered.current = true;
    startLogin();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth.isLoading, auth.activeNavigator, auth.isAuthenticated, auth.error]);

  const handleLogin = (e: React.FormEvent) => {
    e.preventDefault();
    startLogin();
  };

  const displayError = error || auth.error?.message || '';

  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));

  const toggleDark = () => {
    const next = !isDark;
    setIsDark(next);
    const themeVal = next ? 'dark' : 'light';
    document.documentElement.classList.toggle('dark', next);
    document.documentElement.setAttribute('data-mantine-color-scheme', themeVal);
    safeStorage.setItem('admin-color-scheme', themeVal);
    document.cookie = `asm-theme=${themeVal}; path=/; max-age=31536000; SameSite=Strict`;
  };

  const isRTL = locale === 'ar';

  return (
    <div style={{
      minHeight: '100dvh',
      display: 'flex',
      flexDirection: 'column',
      position: 'relative',
      overflow: 'hidden',
      direction: isRTL ? 'rtl' : 'ltr',
      fontFamily: "var(--font-sans)"
    }}>
      {/* Premium Gradient Background */}
      <div style={{
        position: 'absolute',
        inset: 0,
        background: isDark ? 'radial-gradient(circle at center, #1e293b 0%, #0f172a 100%)' : 'radial-gradient(circle at center, #f8fafc 0%, #e2e8f0 100%)',
        zIndex: 0
      }} />

      {/* Floating Transparent Navigation Header */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '24px 32px',
        position: 'relative',
        zIndex: 10,
        flexShrink: 0
      }}>
        <Link to="/" style={{ display: 'flex', alignItems: 'center', gap: 14, textDecoration: 'none' }}>
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            <span style={{
              fontSize: 14,
              fontWeight: 500,
              color: '#FFFFFF',
              letterSpacing: '0.3em',
              fontFamily: "var(--font-heading)",
              lineHeight: 1.1,
              textTransform: 'uppercase'
            }}>
              ASM Track
            </span>
          </div>
        </Link>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <button
            type="button"
            onClick={toggleDark}
            title={isDark ? 'Light mode' : 'Dark mode'}
            style={{
              width: 32,
              height: 32,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'rgba(255, 255, 255, 0.15)',
              backdropFilter: 'blur(8px)',
              border: 'none',
              borderRadius: 4,
              color: '#FFFFFF',
              cursor: 'pointer',
              transition: 'background-color 0.33s'
            }}
            onMouseEnter={e => { (e.currentTarget as HTMLElement).style.backgroundColor = 'rgba(255, 255, 255, 0.25)'; }}
            onMouseLeave={e => { (e.currentTarget as HTMLElement).style.backgroundColor = 'rgba(255, 255, 255, 0.15)'; }}
          >
            {isDark ? <IconSun size={14} /> : <IconMoon size={14} />}
          </button>
          <LanguageSelector variant="landing" scrolled={false} />
        </div>
      </div>

      {/* Centered Model-Style Overlay Title + Minimal Card */}
      <div style={{
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        position: 'relative',
        zIndex: 10
      }}>
        {/* Floating Model Text Overlay */}
        <div style={{
          textAlign: 'center',
          color: '#FFFFFF',
          marginBottom: '5vh',
          userSelect: 'none'
        }}>
          <h1 style={{
            fontSize: '40px',
            fontWeight: 500,
            margin: 0,
            letterSpacing: '0.05em',
            fontFamily: "var(--font-heading)"
          }}>
            ASM Track
          </h1>
          <p style={{
            fontSize: '14px',
            color: 'rgba(255, 255, 255, 0.75)',
            marginTop: 8,
            margin: 0,
            fontWeight: 400
          }}>
            {t.loginPage.brandTagline}
          </p>
        </div>

        {/* Minimal Login Container Card */}
        <div style={{
          width: '100%',
          maxWidth: 380,
          background: isDark ? 'rgba(23, 26, 32, 0.85)' : 'rgba(255, 255, 255, 0.85)',
          backdropFilter: 'blur(16px)',
          borderRadius: 4,
          padding: '40px 36px 32px',
          textAlign: isRTL ? 'right' : 'left',
          boxShadow: 'none',
          border: 'none'
        }}>
          <h2 style={{
            fontSize: 20,
            fontWeight: 500,
            color: isDark ? '#F3F3F7' : '#171A20',
            lineHeight: 1.3,
            margin: 0
          }}>
            {t.loginPage.welcomeBack}
          </h2>
          <p style={{
            fontSize: 13,
            color: isDark ? '#A4A4AD' : '#5C5E62',
            margin: '6px 0 28px',
            lineHeight: 1.5,
            fontWeight: 400
          }}>
            {t.loginPage.signInToContinue}
          </p>

          {/* Error area */}
          {displayError && (
            <div style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '10px 14px',
              background: isDark ? 'rgba(239, 68, 68, 0.15)' : 'rgba(220, 38, 36, 0.08)',
              border: `1px solid ${isDark ? '#EF4444' : '#DC2626'}`,
              borderRadius: 4,
              marginBottom: 20,
              flexDirection: isRTL ? 'row-reverse' : 'row'
            }}>
              <IconAlertCircle size={14} style={{ color: isDark ? '#EF4444' : '#DC2626', flexShrink: 0 }} />
              <span style={{ fontSize: 12, fontWeight: 500, color: isDark ? '#EF4444' : '#DC2626' }}>{displayError}</span>
            </div>
          )}

          <form onSubmit={handleLogin} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {/* Submit CTA */}
            <button
              type="submit"
              disabled={loading}
              style={{
                width: '100%',
                height: 40,
                background: loading ? (isDark ? '#393C41' : '#EEEEEE') : '#3E6AE1',
                color: '#FFFFFF',
                fontSize: 13,
                fontWeight: 500,
                border: 'none',
                borderRadius: 4,
                cursor: loading ? 'not-allowed' : 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 8,
                letterSpacing: '0.02em',
                textTransform: 'lowercase',
                fontFamily: 'inherit'
              }}
              onMouseEnter={e => { if (!loading) (e.currentTarget as HTMLElement).style.backgroundColor = '#2B52C3'; }}
              onMouseLeave={e => { if (!loading) (e.currentTarget as HTMLElement).style.backgroundColor = '#3E6AE1'; }}
            >
              {loading ? (
                <><svg className="animate-spin" width="14" height="14" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" strokeOpacity="0.25"/><path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" strokeWidth="3" strokeLinecap="round"/></svg> {t.loginPage.buttonLoading}</>
              ) : (
                <>{t.loginPage.button}</>
              )}
            </button>
          </form>

          {/* Contact Support */}
          <div style={{
            marginTop: 28,
            paddingTop: 24,
            borderTop: `1px solid ${isDark ? 'rgba(255,255,255,0.08)' : '#EEEEEE'}`,
            textAlign: 'center'
          }}>
            <p style={{ fontSize: 12, color: isDark ? '#A4A4AD' : '#5C5E62', fontWeight: 400, margin: 0 }}>
              {t.loginPage.needAccess}{' '}
              <a href="mailto:contact@asmtrack.com" style={{ color: '#3E6AE1', fontWeight: 500, textDecoration: 'none' }}>
                {t.loginPage.contactTeam}
              </a>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
