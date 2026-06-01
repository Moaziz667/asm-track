
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { lazy as dynamic } from 'react';
import { useT, useLocaleContext } from '@/lib/LocaleContext';
import LanguageSelector from '@/components/LanguageSelector';

const MapSection = dynamic(
  () => import('./map/MapSection').then(m => ({ default: m.MapSection }))
);

// Warm Cream Editorial Design Palette
const T = {
  bg:      '#FAF7F2',
  surface: '#FFFFFF',
  raised:  '#F0EBE3',
  dark:    '#1A1614',
  ink:     '#2D2520',
  sub:     '#3D3530',
  muted:   '#6B5F55',
  soft:    '#9C8F85',
  border:  '#E5DDD5',
  border2: '#EDE8E0',
  accent:  '#C4881A',
  accentBg:'rgba(196,136,26,0.10)',
  live:    '#16A34A',
};

const Logo = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
    <div style={{ width: 28, height: 28, background: T.accent, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, borderRadius: 6, boxShadow: '0 1px 4px rgba(196,136,26,0.35)' }}>
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M5 19H19V12" strokeDasharray="3 3" opacity="0.5" strokeWidth="2" />
        <path d="M5 19L12 5L19 12" strokeWidth="3" />
        <circle cx="5" cy="19" r="2" fill="white" stroke="none" />
        <circle cx="19" cy="19" r="1.5" fill="white" opacity="0.5" stroke="none" />
        <circle cx="12" cy="5" r="2" fill="white" stroke="none" />
        <circle cx="19" cy="12" r="3" fill="white" stroke="none" />
      </svg>
    </div>
    <span style={{ fontFamily: 'var(--font-space-grotesk)', fontWeight: 700, fontSize: 15, letterSpacing: '-0.03em', color: T.dark }}>
      ASM <span style={{ color: T.soft, fontWeight: 400 }}>Track</span>
    </span>
  </div>
);

const LogoLight = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
    <div style={{ width: 28, height: 28, background: T.accent, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, borderRadius: 6, boxShadow: '0 1px 4px rgba(196,136,26,0.35)' }}>
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M5 19H19V12" strokeDasharray="3 3" opacity="0.5" strokeWidth="2" />
        <path d="M5 19L12 5L19 12" strokeWidth="3" />
        <circle cx="5" cy="19" r="2" fill="white" stroke="none" />
        <circle cx="19" cy="19" r="1.5" fill="white" opacity="0.5" stroke="none" />
        <circle cx="12" cy="5" r="2" fill="white" stroke="none" />
        <circle cx="19" cy="12" r="3" fill="white" stroke="none" />
      </svg>
    </div>
    <span style={{ fontFamily: 'var(--font-space-grotesk)', fontWeight: 700, fontSize: 15, letterSpacing: '-0.03em', color: '#FAF7F2' }}>
      ASM <span style={{ color: 'rgba(250,247,242,0.45)', fontWeight: 400 }}>Track</span>
    </span>
  </div>
);

// ── Feature Card 1: Fleet Shuffler ─────────────────────────────────────────
function FleetShuffler({ t }: { t: any }) {
  const { locale } = useLocaleContext();
  const drivers = [
    { name: 'Mohamed Trabelsi', route: 'T-042', status: locale === 'ar' ? 'في الطريق' : locale === 'en' ? 'EN ROUTE' : 'EN ROUTE', stops: locale === 'ar' ? '٧ / ١٢' : '7 / 12' },
    { name: 'Sonia Ben Ali',    route: 'T-038', status: locale === 'ar' ? 'توصيل' : locale === 'en' ? 'DELIVERY' : 'LIVRAISON', stops: locale === 'ar' ? '١١ / ١٥' : '11 / 15' },
    { name: 'Khaled Mansour',   route: 'T-051', status: locale === 'ar' ? 'عودة' : locale === 'en' ? 'RETURN' : 'RETOUR', stops: locale === 'ar' ? '٩ / ٩' : '9 / 9' },
  ];
  const [cards, setCards] = useState(drivers);
  useEffect(() => {
    const id = setInterval(() => {
      setCards(prev => { const next = [...prev]; next.push(next.shift()!); return next; });
    }, 2800);
    return () => clearInterval(id);
  }, []);

  const routeLabel = locale === 'ar' ? 'مسار' : locale === 'en' ? 'Route' : 'Tournée';
  const stopsLabel = locale === 'ar' ? 'محطة' : locale === 'en' ? 'stops' : 'arrêts';

  return (
    <div style={{ position: 'relative', height: 156 }}>
      {cards.map((d, i) => (
        <div key={d.name} style={{
          position: 'absolute', left: 0, right: 0, top: i * 12,
          background: i === 0 ? T.surface : T.bg,
          border: `1px solid ${i === 0 ? T.border : T.border2}`,
          borderRadius: 10, padding: '12px 14px',
          opacity: i === 0 ? 1 : i === 1 ? 0.55 : 0.25,
          transform: `scale(${1 - i * 0.04})`,
          transformOrigin: 'top center',
          transition: 'all 0.55s cubic-bezier(0.34, 1.56, 0.64, 1)',
          zIndex: 3 - i,
          boxShadow: i === 0 ? '0 2px 12px rgba(26,22,20,0.06)' : 'none',
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexDirection: locale === 'ar' ? 'row-reverse' : 'row' }}>
            <div style={{ textAlign: locale === 'ar' ? 'right' : 'left' }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: T.dark, fontFamily: 'var(--font-space-grotesk)', letterSpacing: '-0.02em' }}>{d.name}</div>
              <div style={{ fontSize: 10, color: T.soft, marginTop: 2, fontFamily: 'monospace' }}>
                {locale === 'ar' ? `${d.stops} ${stopsLabel} · ${routeLabel} ${d.route}` : `${routeLabel} ${d.route} · ${d.stops} ${stopsLabel}`}
              </div>
            </div>
            <div style={{ fontSize: 9, fontWeight: 700, color: T.muted, background: T.raised, padding: '3px 8px', borderRadius: 4, fontFamily: 'monospace', letterSpacing: '0.05em' }}>
              {d.status}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

// ── Feature Card 2: Dispatch Typewriter ────────────────────────────────────
function DispatchTypewriter() {
  const { locale } = useLocaleContext();
  const messages = locale === 'ar' ? [
    'تم تحسين المسار T-042 — ٢٣ محطة',
    'أحمد في الطريق · الوصول ١٤:٢٣',
    'تم تأكيد الشحنة #8821 ✓',
    'إعادة توجيه ٣ طرود ← مستودع الشمال',
    'تفعيل إعادة التعيين التلقائي',
    'اكتملت T-051 — العودة للمستودع',
  ] : locale === 'en' ? [
    'Route T-042 optimized — 23 stops',
    'Ahmed en route · ETA 2:23 PM',
    'Delivery #8821 confirmed ✓',
    '3 packages rerouted → North Depot',
    'Auto-reassignment active',
    'T-051 completed — back to depot',
  ] : [
    'Tournée T-042 optimisée — 23 arrêts',
    'Ahmed en route · ETA 14:23',
    'Livraison #8821 confirmée ✓',
    '3 colis redirigés → dépôt Nord',
    'Réaffectation auto activée',
    'T-051 terminée — retour dépôt',
  ];

  const [lineIdx, setLineIdx] = useState(0);
  const [displayed, setDisplayed] = useState('');
  const [charIdx, setCharIdx] = useState(0);
  const [log, setLog] = useState<string[]>([]);

  useEffect(() => {
    if (charIdx < messages[lineIdx].length) {
      const t = setTimeout(() => { setDisplayed(messages[lineIdx].slice(0, charIdx + 1)); setCharIdx(c => c + 1); }, 34);
      return () => clearTimeout(t);
    }
    const t = setTimeout(() => {
      setLog(prev => [...prev.slice(-3), messages[lineIdx]]);
      setLineIdx(i => (i + 1) % messages.length);
      setDisplayed(''); setCharIdx(0);
    }, 1600);
    return () => clearTimeout(t);
  }, [charIdx, lineIdx, messages]);

  const badgeText = locale === 'ar' ? 'التوزيع المباشر' : locale === 'en' ? 'DISPATCH LIVE' : 'DISPATCH LIVE';

  return (
    <div style={{ fontFamily: 'monospace', fontSize: 11, textAlign: locale === 'ar' ? 'right' : 'left' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 14, flexDirection: locale === 'ar' ? 'row-reverse' : 'row' }}>
        <span style={{ width: 6, height: 6, borderRadius: '50%', background: T.accent, display: 'inline-block', boxShadow: `0 0 0 3px ${T.accentBg}` }} />
        <span style={{ fontSize: 10, fontWeight: 700, color: T.soft, letterSpacing: '0.06em' }}>{badgeText}</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 5, direction: 'ltr', alignItems: locale === 'ar' ? 'flex-end' : 'flex-start' }}>
        {log.map((l, i) => (
          <div key={i} style={{ color: T.soft, fontSize: 11, opacity: 0.35 + i * 0.2 }}>&gt; {l}</div>
        ))}
        <div style={{ color: T.muted }}>&gt; {displayed}<span style={{ animation: 'blink 0.8s step-end infinite', color: T.accent }}>█</span></div>
      </div>
    </div>
  );
}

// ── Feature Card 3: Route Scheduler ───────────────────────────────────────
function RouteScheduler() {
  const { locale } = useLocaleContext();
  const days = locale === 'ar'
    ? ['ن', 'ث', 'ر', 'خ', 'ج', 'س', 'ح']
    : locale === 'en'
    ? ['M', 'T', 'W', 'T', 'F', 'S', 'S']
    : ['L', 'M', 'M', 'J', 'V', 'S', 'D'];

  const [active, setActive] = useState(1);
  const [saved, setSaved] = useState(false);
  useEffect(() => {
    let d = 1;
    const run = () => {
      setActive(d); setSaved(false);
      d = (d + 1) % 7;
      setTimeout(() => setSaved(true), 1300);
    };
    run();
    const id = setInterval(run, 2200);
    return () => clearInterval(id);
  }, []);

  const routes = locale === 'ar' ? [
    'T-042 · ١٢ محطة',
    'T-038 · ٨ محطات',
    'T-051 · ١٥ محطة',
  ] : locale === 'en' ? [
    'T-042 · 12 stops',
    'T-038 · 8 stops',
    'T-051 · 15 stops',
  ] : [
    'T-042 · 12 arrêts',
    'T-038 · 8 arrêts',
    'T-051 · 15 arrêts',
  ];

  const dayNames = locale === 'ar'
    ? ['الإثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت', 'الأحد']
    : locale === 'en'
    ? ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
    : ['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'];

  return (
    <div style={{ textAlign: locale === 'ar' ? 'right' : 'left' }}>
      <div style={{ display: 'flex', gap: 5, marginBottom: 14, flexDirection: locale === 'ar' ? 'row-reverse' : 'row' }}>
        {days.map((d, i) => (
          <div key={i} style={{
            flex: 1, height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 10, fontWeight: 700, borderRadius: 6, fontFamily: 'monospace',
            background: i === active ? T.accent : T.raised,
            color: i === active ? '#fff' : T.soft,
            border: `1px solid ${i === active ? T.accent : T.border2}`,
            transition: 'all 0.3s cubic-bezier(0.34, 1.56, 0.64, 1)',
            transform: i === active ? 'scale(1.1)' : 'scale(1)',
          }}>{d}</div>
        ))}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {routes.map((r, i) => (
          <div key={i} style={{ padding: '8px 10px', borderRadius: 7, background: T.raised, border: `1px solid ${T.border2}`, fontSize: 11, color: T.muted, fontFamily: 'monospace', display: 'flex', justifyContent: 'space-between', flexDirection: locale === 'ar' ? 'row-reverse' : 'row' }}>
            <span>{r}</span><span style={{ color: T.soft }}>{dayNames[active]}</span>
          </div>
        ))}
      </div>
      {saved && (
        <div style={{ marginTop: 10, fontSize: 10, color: T.accent, fontFamily: 'monospace', fontWeight: 700, letterSpacing: '0.05em' }}>
          {locale === 'ar' ? '✓ تم جدولة المسارات' : locale === 'en' ? '✓ Routes scheduled' : '✓ Tournées planifiées'}
        </div>
      )}
    </div>
  );
}

// ── Main Component ────────────────────────────────────────────────────────
export default function LandingPage() {
  const t = useT();
  const { locale } = useLocaleContext();
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    gsap.registerPlugin(ScrollTrigger);
    const ctx = gsap.context(() => {
      gsap.from('[data-hero]', { y: 40, opacity: 0, duration: 1, stagger: 0.1, ease: 'power3.out', delay: 0.15 });
      gsap.from('[data-feature]', {
        y: 28, opacity: 0, duration: 0.8, stagger: 0.12, ease: 'power3.out',
        scrollTrigger: { trigger: '[data-features]', start: 'top 78%' },
      });
      gsap.from('[data-manifesto]', {
        y: 18, opacity: 0, duration: 0.9, stagger: 0.1, ease: 'power3.out',
        scrollTrigger: { trigger: '[data-philosophy]', start: 'top 72%' },
      });
      gsap.from('[data-step]', {
        y: 22, opacity: 0, duration: 0.7, stagger: 0.1, ease: 'power3.out',
        scrollTrigger: { trigger: '[data-protocol]', start: 'top 78%' },
      });
    });
    const onScroll = () => setScrolled(window.scrollY > 70);
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => { ctx.revert(); window.removeEventListener('scroll', onScroll); };
  }, []);

  const navLinks = [
    { label: t.landingPage.navFeatures, href: '#features' },
    { label: t.landingPage.navMethod, href: '#protocol' },
    { label: t.landingPage.navContact, href: '#footer'   },
  ];

  const isRTL = locale === 'ar';

  return (
    <div style={{ background: T.bg, minHeight: '100dvh', color: T.dark, fontFamily: 'var(--font-space-grotesk)', direction: isRTL ? 'rtl' : 'ltr' }}>

      {/* Noise Background Overlay */}
      <div aria-hidden style={{ position: 'fixed', inset: 0, zIndex: 0, pointerEvents: 'none', opacity: 0.025,
        backgroundImage: `url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.75' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E")` }} />

      {/* Premium Sticky Navigation Bar */}
      <nav style={{
        position: 'fixed', top: 20, left: '50%', transform: 'translateX(-50%)', zIndex: 100,
        display: 'flex', alignItems: 'center', gap: 24,
        padding: scrolled ? '10px 24px' : '12px 28px',
        background: scrolled ? 'rgba(250,247,242,0.88)' : 'transparent',
        backdropFilter: scrolled ? 'blur(20px)' : 'none',
        border: `1px solid ${scrolled ? T.border : 'transparent'}`,
        borderRadius: 100,
        transition: 'all 0.3s cubic-bezier(0.25, 0.46, 0.45, 0.94)',
        whiteSpace: 'nowrap',
        boxShadow: scrolled ? '0 4px 24px rgba(26,22,20,0.08)' : 'none',
        flexDirection: isRTL ? 'row-reverse' : 'row',
      }}>
        {scrolled ? <Logo /> : <LogoLight />}
        <div style={{ display: 'flex', gap: 4, flexDirection: isRTL ? 'row-reverse' : 'row' }}>
          {navLinks.map(link => (
            <a key={link.label} href={link.href} style={{
              fontSize: 12, fontWeight: 600,
              color: scrolled ? T.muted : 'rgba(250,247,242,0.65)',
              textDecoration: 'none', padding: '6px 12px', borderRadius: 8, transition: 'color 0.15s',
            }}
              onMouseEnter={e => (e.currentTarget.style.color = scrolled ? T.dark : '#FAF7F2')}
              onMouseLeave={e => (e.currentTarget.style.color = scrolled ? T.muted : 'rgba(250,247,242,0.65)')}
            >{link.label}</a>
          ))}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexDirection: isRTL ? 'row-reverse' : 'row' }}>
          <LanguageSelector variant="landing" scrolled={scrolled} />
          <Link to="/login" style={{
            fontSize: 12, fontWeight: 700, color: '#fff', background: T.accent,
            padding: '8px 18px', borderRadius: 8, textDecoration: 'none',
            letterSpacing: '-0.02em', transition: 'transform 0.15s cubic-bezier(0.25, 0.46, 0.45, 0.94)',
            boxShadow: '0 1px 6px rgba(196,136,26,0.35)',
          }}
            onMouseEnter={e => (e.currentTarget.style.transform = 'scale(1.04)')}
            onMouseLeave={e => (e.currentTarget.style.transform = 'scale(1)')}
          >{t.landingPage.navLogin}</Link>
        </div>
      </nav>

      {/* ── HERO SECTION ─────────────────────────────────────────────────── */}
      <section style={{ height: '100dvh', position: 'relative', display: 'flex', overflow: 'hidden' }}>
        {/* Full-bleed high contrast logistics image */}
        <div style={{ position: 'absolute', inset: 0,
          backgroundImage: 'url(https://images.unsplash.com/photo-1586528116311-ad8dd3c8310d?auto=format&fit=crop&w=2000&q=80)',
          backgroundSize: 'cover', backgroundPosition: 'center', filter: 'grayscale(30%) sepia(15%)' }} />
        <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to top, #FAF7F2 0%, rgba(250,247,242,0.92) 20%, rgba(26,22,20,0.55) 65%, rgba(26,22,20,0.2) 100%)' }} />

        {/* Hero Copy (Bottom offset) */}
        <div style={{
          position: 'relative', zIndex: 1, alignSelf: 'flex-end',
          padding: '0 clamp(40px, 6vw, 96px) clamp(60px, 7vh, 96px)',
          maxWidth: 920,
          textAlign: isRTL ? 'right' : 'left'
        }}>
          <div data-hero style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.14em', color: T.muted, textTransform: 'uppercase', marginBottom: 28, fontFamily: 'monospace' }}>
            {t.landingPage.heroBadge}
          </div>
          <h1 data-hero style={{ margin: 0, lineHeight: 0.88 }}>
            <span style={{ display: 'block', fontFamily: 'var(--font-space-grotesk)', fontWeight: 800, fontSize: 'clamp(58px, 7.5vw, 104px)', letterSpacing: '-0.055em', color: T.dark }}>
              {t.landingPage.heroTitle1}
            </span>
            <span style={{ display: 'block', fontFamily: 'var(--font-dm-serif)', fontStyle: 'italic', fontWeight: 400, fontSize: 'clamp(62px, 8vw, 110px)', letterSpacing: '-0.04em', color: T.accent }}>
              {t.landingPage.heroTitle2}
            </span>
          </h1>
          <p data-hero style={{ fontSize: 15, color: T.muted, maxWidth: 420, lineHeight: 1.75, margin: '32px 0 40px', fontWeight: 400 }}>
            {t.landingPage.heroDesc}
          </p>
          <div data-hero style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', flexDirection: isRTL ? 'row-reverse' : 'row' }}>
            <a href="mailto:contact@asmtrack.com" style={{
              display: 'inline-flex', alignItems: 'center', gap: 8,
              fontSize: 13, fontWeight: 700, color: '#fff', background: T.accent,
              padding: '14px 28px', borderRadius: 8, textDecoration: 'none',
              letterSpacing: '-0.02em', transition: 'transform 0.15s cubic-bezier(0.25, 0.46, 0.45, 0.94)',
              boxShadow: '0 2px 12px rgba(196,136,26,0.35)',
              flexDirection: isRTL ? 'row-reverse' : 'row',
            }}
              onMouseEnter={e => (e.currentTarget.style.transform = 'scale(1.03)')}
              onMouseLeave={e => (e.currentTarget.style.transform = 'scale(1)')}
            >
              {t.landingPage.heroCta}
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" style={{ transform: isRTL ? 'rotate(180deg)' : 'none' }}>
                <path d="M5 12h14M12 5l7 7-7 7"/>
              </svg>
            </a>
            <Link to="/login" style={{ fontSize: 13, fontWeight: 600, color: T.muted, textDecoration: 'none', padding: '14px 16px', transition: 'color 0.15s' }}
              onMouseEnter={e => (e.currentTarget.style.color = T.dark)}
              onMouseLeave={e => (e.currentTarget.style.color = T.muted)}
            >{t.landingPage.heroDashboard}</Link>
          </div>
        </div>

        {/* Telemetry Stats Card */}
        <div data-hero style={{
          position: 'absolute',
          [isRTL ? 'left' : 'right']: 'clamp(40px, 6vw, 96px)',
          bottom: 'clamp(60px, 7vh, 96px)',
          zIndex: 1, display: 'flex', gap: 36, padding: '22px 28px',
          background: 'rgba(250,247,242,0.75)', backdropFilter: 'blur(20px)',
          border: `1px solid ${T.border}`, borderRadius: 16,
          boxShadow: '0 4px 24px rgba(26,22,20,0.08)',
          flexDirection: isRTL ? 'row-reverse' : 'row',
        }}>
          {[[t.landingPage.statDeliveries, '10k+'], [t.landingPage.statOptimization, '< 2s'], [t.landingPage.statUptime, '99.9%']].map(([label, val]) => (
            <div key={label} style={{ textAlign: 'center' }}>
              <div style={{ fontSize: 19, fontWeight: 800, color: T.dark, letterSpacing: '-0.04em', lineHeight: 1 }}>{val}</div>
              <div style={{ fontSize: 10, color: T.soft, marginTop: 4, fontWeight: 600, letterSpacing: '0.05em' }}>{label}</div>
            </div>
          ))}
        </div>
      </section>

      {/* ── FEATURES SECTION ─────────────────────────────────────────────── */}
      <section id="features" data-features style={{ padding: 'clamp(80px, 10vh, 130px) clamp(40px, 6vw, 96px)', background: T.bg }}>
        <div style={{ maxWidth: 1200, margin: '0 auto' }}>
          <div style={{ textAlign: 'center', marginBottom: 72 }}>
            <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.14em', color: T.soft, textTransform: 'uppercase', marginBottom: 16, fontFamily: 'monospace' }}>
              {t.landingPage.featuresTitle}
            </div>
            <h2 style={{ fontSize: 'clamp(34px, 4vw, 52px)', fontWeight: 800, letterSpacing: '-0.05em', margin: 0, color: T.dark }}>
              {t.landingPage.featuresSubtitle1}{' '}
              <span style={{ fontFamily: 'var(--font-dm-serif)', fontStyle: 'italic', fontWeight: 400, color: T.accent }}>
                {t.landingPage.featuresSubtitle2}
              </span>
            </h2>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 18 }}>
            {[
              { label: t.landingPage.featureRealtime, desc: t.landingPage.featureRealtimeDesc, inner: <FleetShuffler t={t} /> },
              { label: t.landingPage.featureDispatch, desc: t.landingPage.featureDispatchDesc, inner: <DispatchTypewriter /> },
              { label: t.landingPage.featureErp, desc: t.landingPage.featureErpDesc, inner: <RouteScheduler /> },
            ].map((card, i) => (
              <div data-feature key={i} style={{
                background: T.surface, border: `1px solid ${T.border}`,
                borderRadius: 20, padding: '26px 24px',
                display: 'flex', flexDirection: 'column', gap: 24,
                boxShadow: '0 2px 12px rgba(26,22,20,0.04)',
                transition: 'border-color 0.25s, transform 0.25s, box-shadow 0.25s',
                textAlign: isRTL ? 'right' : 'left',
              }}
                onMouseEnter={e => { const el = e.currentTarget as HTMLElement; el.style.borderColor = T.accent; el.style.transform = 'translateY(-3px)'; el.style.boxShadow = '0 8px 32px rgba(196,136,26,0.1)'; }}
                onMouseLeave={e => { const el = e.currentTarget as HTMLElement; el.style.borderColor = T.border; el.style.transform = 'translateY(0)'; el.style.boxShadow = '0 2px 12px rgba(26,22,20,0.04)'; }}
              >
                <div style={{ flex: 1, direction: 'ltr' }}>{card.inner}</div>
                <div>
                  <div style={{ fontSize: 14, fontWeight: 700, color: T.dark, letterSpacing: '-0.03em', marginBottom: 6 }}>{card.label}</div>
                  <div style={{ fontSize: 12, color: T.muted, lineHeight: 1.7 }}>{card.desc}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── TELEMETRY MAP SECTION ────────────────────────────────────────── */}
      <MapSection />

      {/* ── PHILOSOPHY SECTION (Dark Inverse Block) ───────────────────────── */}
      <section data-philosophy style={{ padding: 'clamp(80px, 10vh, 130px) clamp(40px, 6vw, 96px)', background: T.dark, position: 'relative', overflow: 'hidden' }}>
        <div style={{ position: 'absolute', inset: 0,
          backgroundImage: 'url(https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=1400&q=60)',
          backgroundSize: 'cover', backgroundPosition: 'center', opacity: 0.05, filter: 'grayscale(100%)' }} />
        <div style={{ maxWidth: 900, margin: '0 auto', position: 'relative', zIndex: 1, textAlign: isRTL ? 'right' : 'left' }}>
          <div data-manifesto style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.14em', color: 'rgba(250,247,242,0.35)', textTransform: 'uppercase', marginBottom: 44, fontFamily: 'monospace' }}>
            {t.landingPage.manifestoTitle}
          </div>
          <p data-manifesto style={{ fontSize: 15, color: 'rgba(250,247,242,0.45)', marginBottom: 28, lineHeight: 1.85, maxWidth: 560 }}>
            {t.landingPage.manifestoDesc}
          </p>
          <div data-manifesto>
            <span style={{ fontFamily: 'var(--font-dm-serif)', fontStyle: 'italic', fontSize: 'clamp(40px, 5vw, 68px)', fontWeight: 400, color: '#FAF7F2', letterSpacing: '-0.03em', lineHeight: 1.05 }}>
              {t.landingPage.manifestoFocus1}{' '}
              <span style={{ color: T.accent }}>{t.landingPage.manifestoFocus2}</span>
            </span>
          </div>
          <div data-manifesto style={{ marginTop: 56, paddingTop: 44, borderTop: '1px solid rgba(250,247,242,0.08)', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 40 }}>
            {[
              [t.landingPage.manifestoPillar1, t.landingPage.manifestoPillar1Desc],
              [t.landingPage.manifestoPillar2, t.landingPage.manifestoPillar2Desc],
              [t.landingPage.manifestoPillar3, t.landingPage.manifestoPillar3Desc],
            ].map(([title, body]) => (
              <div key={title}>
                <div style={{ fontSize: 13, fontWeight: 700, color: '#FAF7F2', letterSpacing: '-0.02em', marginBottom: 10 }}>{title}</div>
                <div style={{ fontSize: 12, color: 'rgba(250,247,242,0.45)', lineHeight: 1.75 }}>{body}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── METHOD / PROCESS PROTOCOL ────────────────────────────────────── */}
      <section id="protocol" data-protocol style={{ padding: 'clamp(80px, 10vh, 130px) clamp(40px, 6vw, 96px)', background: T.bg }}>
        <div style={{ maxWidth: 1200, margin: '0 auto' }}>
          <div style={{ textAlign: 'center', marginBottom: 72 }}>
            <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.14em', color: T.soft, textTransform: 'uppercase', marginBottom: 16, fontFamily: 'monospace' }}>
              {t.landingPage.methodTitle}
            </div>
            <h2 style={{ fontSize: 'clamp(34px, 4vw, 52px)', fontWeight: 800, letterSpacing: '-0.05em', margin: 0, color: T.dark }}>
              {t.landingPage.methodSubtitle1}{' '}
              <span style={{ fontFamily: 'var(--font-dm-serif)', fontStyle: 'italic', fontWeight: 400, color: T.accent }}>
                {t.landingPage.methodSubtitle2}
              </span>
            </h2>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 18 }}>
            {[
              { n: '01', title: t.landingPage.methodStep1, body: t.landingPage.methodStep1Desc },
              { n: '02', title: t.landingPage.methodStep2, body: t.landingPage.methodStep2Desc },
              { n: '03', title: t.landingPage.methodStep3, body: t.landingPage.methodStep3Desc },
            ].map(step => (
              <div data-step key={step.n} style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 20, padding: '32px 28px', boxShadow: '0 2px 12px rgba(26,22,20,0.04)', textAlign: isRTL ? 'right' : 'left' }}>
                <div style={{ fontFamily: 'monospace', fontSize: 11, fontWeight: 700, color: T.accent, letterSpacing: '0.08em', marginBottom: 22 }}>{step.n}</div>
                <div style={{ fontSize: 17, fontWeight: 800, color: T.dark, letterSpacing: '-0.04em', lineHeight: 1.25, marginBottom: 14 }}>{step.title}</div>
                <div style={{ fontSize: 13, color: T.muted, lineHeight: 1.75 }}>{step.body}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── CALL TO ACTION SECTION ───────────────────────────────────────── */}
      <section style={{ padding: 'clamp(80px, 10vh, 130px) clamp(40px, 6vw, 96px)', textAlign: 'center', background: T.raised }}>
        <div style={{ maxWidth: 580, margin: '0 auto' }}>
          <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.14em', color: T.soft, textTransform: 'uppercase', marginBottom: 28, fontFamily: 'monospace' }}>
            {t.landingPage.ctaTitle}
          </div>
          <h2 style={{ fontSize: 'clamp(40px, 5vw, 64px)', fontWeight: 800, letterSpacing: '-0.055em', margin: '0 0 24px', color: T.dark, lineHeight: 0.9 }}>
            {t.landingPage.ctaSubtitle1}<br />
            <span style={{ fontFamily: 'var(--font-dm-serif)', fontStyle: 'italic', fontWeight: 400, color: T.accent }}>
              {t.landingPage.ctaSubtitle2}
            </span>
          </h2>
          <p style={{ fontSize: 14, color: T.muted, lineHeight: 1.75, marginBottom: 44 }}>
            {t.landingPage.ctaDesc}
          </p>
          <a href="mailto:contact@asmtrack.com" style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 8,
            fontSize: 14,
            fontWeight: 700,
            color: '#fff',
            background: T.accent,
            padding: '16px 36px',
            borderRadius: 10,
            textDecoration: 'none',
            letterSpacing: '-0.02em',
            transition: 'transform 0.15s cubic-bezier(0.25, 0.46, 0.45, 0.94)',
            boxShadow: '0 2px 16px rgba(196,136,26,0.35)',
            flexDirection: isRTL ? 'row-reverse' : 'row'
          }}
            onMouseEnter={e => (e.currentTarget.style.transform = 'scale(1.03)')}
            onMouseLeave={e => (e.currentTarget.style.transform = 'scale(1)')}
          >{t.landingPage.ctaButton}</a>
        </div>
      </section>

      {/* ── FOOTER SECTION ───────────────────────────────────────────────── */}
      <footer id="footer" style={{
        background: T.dark, borderTop: `1px solid rgba(250,247,242,0.06)`,
        padding: 'clamp(48px, 6vh, 80px) clamp(40px, 6vw, 96px)',
        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 48,
        textAlign: isRTL ? 'right' : 'left',
      }}>
        <div>
          <LogoLight />
          <p style={{ fontSize: 12, color: 'rgba(250,247,242,0.4)', marginTop: 16, lineHeight: 1.75, maxWidth: 220 }}>
            {t.landingPage.footerDesc}
          </p>
        </div>
        <div>
          <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.12em', color: 'rgba(250,247,242,0.3)', textTransform: 'uppercase', marginBottom: 18, fontFamily: 'monospace' }}>
            {t.landingPage.footerNav}
          </div>
          {[
            { label: t.sidebar.items.dashboard, href: '/login' },
            { label: t.sidebar.items.routes, href: '/login' },
            { label: t.sidebar.items.tracking, href: '/login' },
            { label: t.sidebar.items.drivers, href: '/login' },
            { label: t.sidebar.items.dispatch, href: '/login' },
          ].map(l => (
            <div key={l.label} style={{ marginBottom: 10 }}>
              <Link to={l.href} style={{ fontSize: 13, color: 'rgba(250,247,242,0.45)', textDecoration: 'none', transition: 'color 0.15s' }}
                onMouseEnter={e => (e.currentTarget.style.color = '#FAF7F2')}
                onMouseLeave={e => (e.currentTarget.style.color = 'rgba(250,247,242,0.45)')}
              >{l.label}</Link>
            </div>
          ))}
        </div>
        <div>
          <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.12em', color: 'rgba(250,247,242,0.3)', textTransform: 'uppercase', marginBottom: 18, fontFamily: 'monospace' }}>
            {t.landingPage.footerContact}
          </div>
          <div style={{ fontSize: 12, color: 'rgba(250,247,242,0.45)', marginBottom: 10, direction: 'ltr', textAlign: isRTL ? 'right' : 'left' }}>contact@asmtrack.com</div>
          <div style={{ fontSize: 12, color: 'rgba(250,247,242,0.45)' }}>Tunisia, Tunis</div>
          <div style={{ marginTop: 28, fontSize: 10, color: 'rgba(250,247,242,0.2)', fontFamily: 'monospace', letterSpacing: '0.06em' }}>
            {t.landingPage.footerRights}
          </div>
        </div>
      </footer>

      <style>{`
        @keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
      `}</style>
    </div>
  );
}

