
import { useState, useEffect, useRef } from 'react';
import { useLocaleStore, type Locale } from '@/lib/i18n';
import { useLocaleContext } from '@/lib/LocaleContext';

// Circular, pixel-perfect SVGs for Tunisia (AR), France (FR), United States (EN)
export const TNFlag = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" style={{ borderRadius: '50%', flexShrink: 0, display: 'inline-block', verticalAlign: 'middle' }}>
    <rect width="24" height="24" fill="#E70013" />
    <circle cx="12" cy="12" r="6" fill="#FFFFFF" />
    {/* Crescent */}
    <circle cx="11.5" cy="12" r="3" fill="#E70013" />
    <circle cx="12.5" cy="12" r="2.5" fill="#FFFFFF" />
    {/* 5-pointed star centered around (13.5, 12) */}
    <path d="M13.5,10.2 L13.9,11.4 L15.2,11.4 L14.1,12.2 L14.5,13.5 L13.5,12.7 L12.5,13.5 L12.9,12.2 L11.8,11.4 L13.1,11.4 Z" fill="#E70013" />
  </svg>
);

export const FRFlag = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" style={{ borderRadius: '50%', flexShrink: 0, display: 'inline-block', verticalAlign: 'middle' }}>
    <rect width="8" height="24" fill="#0055A5" />
    <rect x="8" width="8" height="24" fill="#FFFFFF" />
    <rect x="16" width="8" height="24" fill="#C8102E" />
  </svg>
);

export const USFlag = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" style={{ borderRadius: '50%', flexShrink: 0, display: 'inline-block', verticalAlign: 'middle' }}>
    <rect width="24" height="24" fill="#C8102E" />
    <rect y="1.85" width="24" height="1.85" fill="#FFFFFF" />
    <rect y="5.55" width="24" height="1.85" fill="#FFFFFF" />
    <rect y="9.25" width="24" height="1.85" fill="#FFFFFF" />
    <rect y="12.95" width="24" height="1.85" fill="#FFFFFF" />
    <rect y="16.65" width="24" height="1.85" fill="#FFFFFF" />
    <rect y="20.35" width="24" height="1.85" fill="#FFFFFF" />
    <rect width="11" height="11" fill="#002F6C" />
    <circle cx="2.2" cy="2.2" r="0.6" fill="#FFFFFF" />
    <circle cx="5.5" cy="2.2" r="0.6" fill="#FFFFFF" />
    <circle cx="8.8" cy="2.2" r="0.6" fill="#FFFFFF" />
    <circle cx="3.85" cy="5.5" r="0.6" fill="#FFFFFF" />
    <circle cx="7.15" cy="5.5" r="0.6" fill="#FFFFFF" />
    <circle cx="2.2" cy="8.8" r="0.6" fill="#FFFFFF" />
    <circle cx="5.5" cy="8.8" r="0.6" fill="#FFFFFF" />
    <circle cx="8.8" cy="8.8" r="0.6" fill="#FFFFFF" />
  </svg>
);

const LANGS: { code: Locale; label: string; Flag: React.ComponentType; name: string }[] = [
  { code: 'ar', label: 'العربية', Flag: TNFlag, name: 'TN' },
  { code: 'fr', label: 'Français', Flag: FRFlag, name: 'FR' },
  { code: 'en', label: 'English', Flag: USFlag, name: 'US' },
];

export default function LanguageSelector({ variant = 'default', scrolled = false }: { variant?: 'default' | 'landing'; scrolled?: boolean }) {
  const { locale: activeLocale, setLocale } = useLocaleStore();
  const [isOpen, setIsOpen] = useState(false);
  const [mounted, setMounted] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Ensure client-side rendering only to prevent hydration mismatch
  useEffect(() => {
    setMounted(true);
  }, []);

  // Close when clicking outside
  useEffect(() => {
    const handleOutsideClick = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('click', handleOutsideClick);
    return () => document.removeEventListener('click', handleOutsideClick);
  }, []);

  const active = LANGS.find(l => l.code === activeLocale) || LANGS[1];
  const isLandingScrolled = variant === 'landing' && scrolled;

  if (!mounted) return null;

  return (
    <div ref={containerRef} style={{ position: 'relative', display: 'inline-block', zIndex: 90 }}>
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          height: variant === 'landing' ? '36px' : '28px',
          padding: '0 12px',
          background: isLandingScrolled
            ? 'rgba(26,22,20,0.06)'
            : variant === 'landing'
            ? 'rgba(250,247,242,0.15)'
            : 'var(--hover-bg)',
          color: isLandingScrolled
            ? '#1A1614'
            : variant === 'landing'
            ? '#FAF7F2'
            : 'var(--text-primary)',
          border: `1px solid ${
            isLandingScrolled
              ? 'rgba(26,22,20,0.15)'
              : variant === 'landing'
              ? 'rgba(250,247,242,0.2)'
              : 'var(--border)'
          }`,
          borderRadius: '99px',
          cursor: 'pointer',
          fontFamily: 'var(--font-space-grotesk)',
          fontSize: '11px',
          fontWeight: 700,
          letterSpacing: '0.04em',
          textTransform: 'uppercase',
          transition: 'all 0.2s',
          outline: 'none',
        }}
        onMouseEnter={e => {
          e.currentTarget.style.background = isLandingScrolled
            ? 'rgba(26,22,20,0.12)'
            : variant === 'landing'
            ? 'rgba(250,247,242,0.25)'
            : 'var(--border)';
          e.currentTarget.style.transform = 'scale(1.02)';
        }}
        onMouseLeave={e => {
          e.currentTarget.style.background = isLandingScrolled
            ? 'rgba(26,22,20,0.06)'
            : variant === 'landing'
            ? 'rgba(250,247,242,0.15)'
            : 'var(--hover-bg)';
          e.currentTarget.style.transform = 'scale(1)';
        }}
      >
        <active.Flag />
        <span>{active.name}</span>
        <svg width="8" height="6" viewBox="0 0 10 6" fill="none" style={{
          transform: isOpen ? 'rotate(180deg)' : 'rotate(0)',
          transition: 'transform 0.2s',
          stroke: 'currentColor',
          strokeWidth: 2,
          strokeLinecap: 'round',
          strokeLinejoin: 'round'
        }}>
          <path d="M1 1L5 5L9 1" />
        </svg>
      </button>

      {isOpen && (
        <div style={{
          position: 'absolute',
          top: 'calc(100% + 6px)',
          [activeLocale === 'ar' ? 'left' : 'right']: 0,
          background: 'var(--surface, #FFFFFF)',
          border: '1px solid var(--border, rgba(0,0,0,0.08))',
          borderRadius: '12px',
          boxShadow: '0 4px 20px rgba(0,0,0,0.08), 0 1px 3px rgba(0,0,0,0.04)',
          minWidth: '130px',
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
                  gap: '10px',
                  width: '100%',
                  padding: '8px 12px',
                  background: isSelected ? 'var(--hover-bg, rgba(0,0,0,0.04))' : 'transparent',
                  color: isSelected ? 'var(--text-primary, #000)' : 'var(--text-secondary, #666)',
                  border: 'none',
                  borderRadius: '8px',
                  cursor: 'pointer',
                  textAlign: activeLocale === 'ar' ? 'right' : 'left',
                  flexDirection: activeLocale === 'ar' ? 'row-reverse' : 'row',
                  fontFamily: 'var(--font-space-grotesk)',
                  fontSize: '12px',
                  fontWeight: isSelected ? 700 : 500,
                  transition: 'background 0.15s, color 0.15s',
                  outline: 'none',
                }}
                onMouseEnter={e => {
                  if (!isSelected) e.currentTarget.style.background = 'var(--hover-bg, rgba(0,0,0,0.02))';
                }}
                onMouseLeave={e => {
                  if (!isSelected) e.currentTarget.style.background = 'transparent';
                }}
              >
                <lang.Flag />
                <span style={{ flex: 1 }}>{lang.label}</span>
              </button>
            );
          })}
        </div>
      )}

      <style>{`
        @keyframes fade-scale-in {
          from { opacity: 0; transform: scale(0.95) translateY(-4px); }
          to { opacity: 1; transform: scale(1) translateY(0); }
        }
      `}</style>
    </div>
  );
}

