import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useNavigate as useRouter } from 'react-router-dom';
import { api } from '@/lib/api';
import { safeStorage } from '@/lib/storage';
import { showSuccessToast } from '@/lib/toast-service';
import { IconMail, IconLock, IconEye, IconEyeOff, IconAlertCircle, IconSun, IconMoon } from '@tabler/icons-react';
import { useT, useLocaleContext } from '@/lib/LocaleContext';
import LanguageSelector from '@/components/LanguageSelector';

export default function LoginPage() {
  const t = useT();
  const { locale } = useLocaleContext();
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPass, setShowPass] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [focused, setFocused] = useState<'email' | 'password' | null>(null);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await api.post('/api/auth/admin/login', { email, password });
      safeStorage.setItem('admin_name', res.data.user?.name ?? res.data.name ?? email.split('@')[0]);
      safeStorage.setItem('admin_role', res.data.user?.role ?? res.data.role ?? 'ADMIN');
      showSuccessToast('successLogin');
      router('/dashboard');
    } catch (err: any) {
      setError(err.response?.data?.message ?? t.loginPage.errorDefault);
    } finally {
      setLoading(false);
    }
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
            {/* Email */}
            <div>
              <label style={{
                display: 'block',
                fontSize: 11,
                fontWeight: 600,
                color: 'var(--text-secondary)',
                marginBottom: 5
              }}>
                {t.loginPage.emailLabel}
              </label>
              <div style={{ position: 'relative' }}>
                <div style={{
                  position: 'absolute',
                  [isRTL ? 'right' : 'left']: 11,
                  top: '50%',
                  transform: 'translateY(-50%)',
                  color: focused === 'email' ? 'var(--brand)' : 'var(--text-muted)',
                  transition: 'color 0.15s',
                  pointerEvents: 'none',
                  display: 'flex'
                }}>
                  <IconMail size={14} />
                </div>
                <input
                  type="email"
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                  onFocus={() => setFocused('email')}
                  onBlur={() => setFocused(null)}
                  required
                  placeholder={t.loginPage.emailPh}
                  autoComplete="email"
                  style={{
                    width: '100%',
                    height: 38,
                    paddingInlineStart: 34,
                    paddingInlineEnd: 11,
                    background: 'var(--app-bg)',
                    border: `1px solid ${focused === 'email' ? 'var(--brand)' : 'var(--border)'}`,
                    borderRadius: 'var(--radius)',
                    fontSize: 13,
                    fontWeight: 500,
                    color: 'var(--text-primary)',
                    outline: 'none',
                    transition: 'border-color 0.15s',
                    fontFamily: 'inherit',
                    boxSizing: 'border-box'
                  }}
                />
              </div>
            </div>

            {/* Password */}
            <div>
              <label style={{
                display: 'block',
                fontSize: 11,
                fontWeight: 600,
                color: 'var(--text-secondary)',
                marginBottom: 5
              }}>
                {t.loginPage.passLabel}
              </label>
              <div style={{ position: 'relative' }}>
                <div style={{
                  position: 'absolute',
                  [isRTL ? 'right' : 'left']: 11,
                  top: '50%',
                  transform: 'translateY(-50%)',
                  color: focused === 'password' ? 'var(--brand)' : 'var(--text-muted)',
                  transition: 'color 0.15s',
                  pointerEvents: 'none',
                  display: 'flex'
                }}>
                  <IconLock size={14} />
                </div>
                <input
                  type={showPass ? 'text' : 'password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  onFocus={() => setFocused('password')}
                  onBlur={() => setFocused(null)}
                  required
                  placeholder={t.loginPage.passPh}
                  autoComplete="current-password"
                  style={{
                    width: '100%',
                    height: 38,
                    paddingInlineStart: 34,
                    paddingInlineEnd: 38,
                    background: 'var(--app-bg)',
                    border: `1px solid ${focused === 'password' ? 'var(--brand)' : 'var(--border)'}`,
                    borderRadius: 'var(--radius)',
                    fontSize: 13,
                    fontWeight: 500,
                    color: 'var(--text-primary)',
                    outline: 'none',
                    transition: 'border-color 0.15s',
                    fontFamily: 'inherit',
                    boxSizing: 'border-box'
                  }}
                />
                <button
                  type="button"
                  onClick={() => setShowPass(s => !s)}
                  style={{
                    position: 'absolute',
                    [isRTL ? 'left' : 'right']: 9,
                    top: '50%',
                    transform: 'translateY(-50%)',
                    color: 'var(--text-muted)',
                    background: 'none',
                    border: 'none',
                    cursor: 'pointer',
                    padding: 4,
                    display: 'flex'
                  }}
                  tabIndex={-1}
                >
                  {showPass ? <IconEyeOff size={14} /> : <IconEye size={14} />}
                </button>
              </div>
            </div>

            {/* Forgot password link */}
            <div style={{ textAlign: isRTL ? 'right' : 'left', marginTop: -2 }}>
              <a
                href="#"
                style={{
                  fontSize: 12,
                  fontWeight: 600,
                  color: 'var(--brand)',
                  textDecoration: 'none'
                }}
                onClick={e => e.preventDefault()}
              >
                Forgot password?
              </a>
            </div>

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
