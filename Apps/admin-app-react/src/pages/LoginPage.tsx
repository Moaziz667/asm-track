import { useState } from 'react';
import { Link } from 'react-router-dom';
import { IconAlertCircle, IconSun, IconMoon } from '@tabler/icons-react';
import { useT, useLocaleContext } from '@/lib/LocaleContext';
import LanguageSelector from '@/components/LanguageSelector';
import { useAuth } from 'react-oidc-context';
import { safeStorage } from '@/lib/storage';

export default function LoginPage() {
  const t = useT();
  const { locale } = useLocaleContext();
  const auth = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleLogin = (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    auth.signinRedirect().catch((err) => {
      setError(err.message);
      setLoading(false);
    });
  };

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
      background: 'var(--app-bg)',
      direction: isRTL ? 'rtl' : 'ltr',
      fontFamily: '"IBM Plex Sans", system-ui, sans-serif'
    }}>
      {/* Top bar with brand + language */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '16px 24px',
        flexShrink: 0
      }}>
        <Link to="/" style={{ display: 'flex', alignItems: 'center', gap: 14, textDecoration: 'none' }}>
          <div style={{ width: 72, height: 72, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', flexShrink: 0 }}>
            <img src="/icon.png" alt="ASM" style={{ width: '100%', height: '100%', objectFit: 'contain' }} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            <span style={{ fontSize: 22, fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.02em', fontFamily: '"IBM Plex Sans", system-ui, sans-serif', lineHeight: 1.1 }}>
              ASM Track
            </span>
            <span style={{ fontSize: 10, fontWeight: 500, color: 'var(--text-muted)', letterSpacing: '0.03em', marginTop: 2 }}>
              {t.loginPage.brandTagline}
            </span>
          </div>
        </Link>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
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
              background: 'var(--hover-bg)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius)',
              color: 'var(--text-secondary)',
              cursor: 'pointer'
            }}
          >
            {isDark ? <IconSun size={14} /> : <IconMoon size={14} />}
          </button>
          <LanguageSelector />
        </div>
      </div>

      {/* Centered login card */}
      <div style={{
        flex: 1,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24
      }}>
        <div style={{
          width: '100%',
          maxWidth: 380,
          background: 'var(--surface)',
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius)',
          padding: '36px 32px 28px',
          textAlign: isRTL ? 'right' : 'left'
        }}>
          {/* Headline */}
          <h1 style={{
            fontSize: 18,
            fontWeight: 600,
            color: 'var(--text-primary)',
            letterSpacing: '-0.02em',
            lineHeight: 1.3,
            margin: 0
          }}>
            {t.loginPage.welcomeBack}
          </h1>
          <p style={{ fontSize: 13, color: 'var(--text-secondary)', margin: '4px 0 24px', lineHeight: 1.5 }}>
            {t.loginPage.signInToContinue}
          </p>

          {/* Error area */}
          {error && (
            <div style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '8px 12px',
              background: 'var(--danger-bg)',
              border: '1px solid var(--danger)',
              borderRadius: 'var(--radius)',
              marginBottom: 16,
              flexDirection: isRTL ? 'row-reverse' : 'row'
            }}>
              <IconAlertCircle size={14} style={{ color: 'var(--danger)', flexShrink: 0 }} />
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--danger)' }}>{error}</span>
            </div>
          )}

          <form onSubmit={handleLogin} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {/* OIDC Login does not need email/password fields */}


            {/* Submit */}
            <button
              type="submit"
              disabled={loading}
              style={{
                width: '100%',
                height: 38,
                marginTop: 4,
                background: loading ? 'var(--border)' : 'var(--brand)',
                color: loading ? 'var(--text-muted)' : '#09090B',
                fontSize: 13,
                fontWeight: 700,
                border: 'none',
                borderRadius: 'var(--radius)',
                cursor: loading ? 'not-allowed' : 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 8,
                letterSpacing: '-0.01em',
                transition: 'opacity 0.15s',
                fontFamily: 'inherit'
              }}
              onMouseEnter={e => { if (!loading) (e.currentTarget as HTMLElement).style.opacity = '0.85'; }}
              onMouseLeave={e => { (e.currentTarget as HTMLElement).style.opacity = '1'; }}
            >
              {loading ? (
                <><svg className="animate-spin" width="14" height="14" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" strokeOpacity="0.25"/><path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" strokeWidth="3" strokeLinecap="round"/></svg> {t.loginPage.buttonLoading}</>
              ) : (
                <>{t.loginPage.button}</>
              )}
            </button>
          </form>

          {/* Contact */}
          <div style={{ marginTop: 24, paddingTop: 20, borderTop: '1px solid var(--border)', textAlign: 'center' }}>
            <p style={{ fontSize: 12, color: 'var(--text-muted)', fontWeight: 500, margin: 0 }}>
              {t.loginPage.needAccess}{' '}
              <a href="mailto:contact@asmtrack.com" style={{ color: 'var(--brand)', fontWeight: 600, textDecoration: 'none' }}>
                {t.loginPage.contactTeam}
              </a>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
