
import { useState, useEffect, useRef } from 'react';
import { useLocaleStore, type Locale } from '@/lib/i18n';
import { IconLanguage } from '@tabler/icons-react';

const LANGS: { code: Locale; label: string }[] = [
  { code: 'ar', label: 'العربية' },
  { code: 'fr', label: 'Français' },
  { code: 'en', label: 'English' },
];

export default function LanguageSelector({ variant = 'default', scrolled = false }: { variant?: 'default' | 'landing'; scrolled?: boolean }) {
  const { locale: activeLocale, setLocale } = useLocaleStore();
  const [isOpen, setIsOpen] = useState(false);
  const [mounted, setMounted] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    const handleOutsideClick = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('click', handleOutsideClick);
    return () => document.removeEventListener('click', handleOutsideClick);
  }, []);

  const isLandingScrolled = variant === 'landing' && scrolled;
  const isRtl = activeLocale === 'ar';

  if (!mounted) return null;

  return (
    <div ref={containerRef} style={{ position: 'relative', display: 'inline-block', zIndex: 90 }}>
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          height: variant === 'landing' ? '36px' : '28px',
          width: variant === 'landing' ? '36px' : '28px',
          padding: 0,
          background: 'transparent',
          color: isLandingScrolled
            ? '#1A1614'
            : variant === 'landing'
            ? '#FAF7F2'
            : 'var(--text-muted)',
          border: 'none',
          borderRadius: 'var(--radius, 4px)',
          cursor: 'pointer',
          transition: 'all 0.2s',
          outline: 'none',
        }}
        onMouseEnter={e => {
          e.currentTarget.style.background = 'var(--hover-bg, #F1F5F9)';
        }}
        onMouseLeave={e => {
          e.currentTarget.style.background = 'transparent';
        }}
      >
        <IconLanguage size={16} />
      </button>

      {isOpen && (
        <div style={{
          position: 'absolute',
          top: '100%',
          [isRtl ? 'right' : 'left']: 0,
          marginTop: '6px',
          background: 'var(--surface, #FFFFFF)',
          border: '1px solid var(--border, rgba(0,0,0,0.08))',
          borderRadius: 'var(--radius, 4px)',
          boxShadow: '0 4px 20px rgba(0,0,0,0.08), 0 1px 3px rgba(0,0,0,0.04)',
          minWidth: '140px',
          padding: '4px',
          display: 'flex',
          flexDirection: 'column',
          gap: '2px',
          animation: 'fade-scale-in 0.15s cubic-bezier(0.16, 1, 0.3, 1)',
        }}>
          {LANGS.map(lang => {
            const isSelected = lang.code === activeLocale;
            return (
              <button
                key={lang.code}
                type="button"
                onClick={() => {
                  setLocale(lang.code);
                  setIsOpen(false);
                }}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  width: '100%',
                  padding: '8px 12px',
                  background: isSelected ? 'var(--hover-bg, #F1F5F9)' : 'transparent',
                  color: isSelected ? 'var(--text-primary, #000)' : 'var(--text-secondary, #666)',
                  border: 'none',
                  borderRadius: 'var(--radius-sm, 2px)',
                  cursor: 'pointer',
                  textAlign: isRtl ? 'right' : 'left',
                  flexDirection: isRtl ? 'row-reverse' : 'row',
                  fontSize: '12px',
                  fontWeight: isSelected ? 600 : 400,
                  transition: 'background 0.15s, color 0.15s',
                  outline: 'none',
                }}
                onMouseEnter={e => {
                  if (!isSelected) e.currentTarget.style.background = 'var(--hover-bg, #F1F5F9)';
                }}
                onMouseLeave={e => {
                  if (!isSelected) e.currentTarget.style.background = 'transparent';
                }}
              >
                <span>{lang.label}</span>
              </button>
            );
          })}
        </div>
      )}

      <style>{`
        @keyframes fade-scale-in {
          from { opacity: 0; transform: scale(0.95); }
          to { opacity: 1; transform: scale(1); }
        }
      `}</style>
    </div>
  );
}
