'use client';

import React, { useEffect, useRef, useState } from 'react';
import { DotLottieReact } from '@lottiefiles/dotlottie-react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useT, useLocaleContext } from '@/lib/LocaleContext';

const ACCENT = '#C4881A';
const INK    = '#1A1614';
const BG     = '#FAF7F2';

const URLS = {
  dispatch: 'https://lottie.host/dcfdf7fe-14b4-407d-bfeb-0f1d748a452e/sH0Dj0X5WN.lottie',
  scooter:  'https://lottie.host/cbce0c34-799a-4bfb-8298-29ffe1dba240/zQ5juWvUF3.lottie',
  delivery: 'https://lottie.host/ce77702b-62e2-43a5-9944-147d95c2d42b/i3mGWDladV.lottie',
  confirm:  'https://lottie.host/f8f02c72-6b04-4ee2-92a6-f3844b29fffc/YKNEZaP6MN.lottie',
};

export function MapSection() {
  const t = useT();
  const { locale } = useLocaleContext();
  const sectionRef = useRef<HTMLDivElement>(null);
  const [prog, setProg]         = useState(0);
  const [sceneIdx, setSceneIdx] = useState(0);

  const scenes = [
    {
      key:   'dispatch' as const,
      num:   '01',
      title: t.mapSection.dispatch.title,
      desc:  t.mapSection.dispatch.desc,
      tag:   t.mapSection.dispatch.tag,
      size:  'min(420px, 66vw)',
      from:  0,    until: 0.28,
    },
    {
      key:   'scooter' as const,
      num:   '02',
      title: t.mapSection.scooter.title,
      desc:  t.mapSection.scooter.desc,
      tag:   t.mapSection.scooter.tag,
      size:  'min(580px, 82vw)',
      from:  0.28, until: 0.56,
    },
    {
      key:   'delivery' as const,
      num:   '03',
      title: t.mapSection.delivery.title,
      desc:  t.mapSection.delivery.desc,
      tag:   t.mapSection.delivery.tag,
      size:  'min(420px, 66vw)',
      from:  0.56, until: 0.82,
    },
    {
      key:   'confirm' as const,
      num:   '04',
      title: t.mapSection.confirm.title,
      desc:  t.mapSection.confirm.desc,
      tag:   t.mapSection.confirm.tag,
      size:  'min(420px, 66vw)',
      from:  0.82, until: 1.00,
    },
  ];

  useEffect(() => {
    if (typeof window === 'undefined') return;
    gsap.registerPlugin(ScrollTrigger);
    const st = ScrollTrigger.create({
      trigger: sectionRef.current,
      start: 'top top',
      end:   'bottom bottom',
      scrub: 1,
      onUpdate: ({ progress: p }) => {
        setProg(p);
        const idx = scenes.findIndex(s => p >= s.from && p < s.until);
        setSceneIdx(idx === -1 ? scenes.length - 1 : idx);
      },
    });
    return () => st.kill();
  }, [scenes]);

  const scene = scenes[sceneIdx];
  const isRTL = locale === 'ar';

  return (
    <section
      ref={sectionRef}
      style={{ position: 'relative', height: '200vh', background: BG, direction: isRTL ? 'rtl' : 'ltr' }}
    >
      <div style={{
        position: 'sticky', top: 0,
        height: '100dvh', overflow: 'hidden',
      }}>

        {/* ── Top/bottom cream fades ───────────────────────────── */}
        <div aria-hidden style={{
          position: 'absolute', inset: 0, zIndex: 20, pointerEvents: 'none',
          background: `linear-gradient(to bottom,
            ${BG} 0%,
            transparent 14%,
            transparent 86%,
            ${BG} 100%)`,
        }} />

        {/* ── Giant background number — the chapter anchor ─────── */}
        <div aria-hidden style={{
          position: 'absolute',
          top: '50%',
          [isRTL ? 'right' : 'left']: '-0.04em',
          [isRTL ? 'left' : 'right']: 'auto',
          transform: 'translateY(-52%)',
          fontSize: 'clamp(200px, 28vw, 340px)',
          fontWeight: 900,
          letterSpacing: '-0.06em',
          lineHeight: 1,
          color: `${ACCENT}0e`,
          userSelect: 'none',
          zIndex: 1,
          transition: 'color 0s',
          pointerEvents: 'none',
        }}>
          {scene.num}
        </div>

        {/* ── Animations — right-biased center ─────────────────── */}
        <div style={{
          position: 'absolute', inset: 0, zIndex: 2,
          display: 'flex', alignItems: 'center',
          justifyContent: 'center',
          paddingLeft: isRTL ? 0 : 'clamp(0px, 12vw, 180px)',
          paddingRight: isRTL ? 'clamp(0px, 12vw, 180px)' : 0,
        }}>
          {scenes.map((s, i) => {
            const isActive = sceneIdx === i;
            return (
              <div key={s.key} style={{
                position: 'absolute',
                width: s.size,
                opacity:   isActive ? 1 : 0,
                transform: `scale(${isActive ? 1 : 0.94})`,
                transition: 'opacity 0.55s ease, transform 0.55s cubic-bezier(0.34,1.2,0.64,1)',
                pointerEvents: 'none',
              }}>
                <DotLottieReact
                  key={isActive ? `${s.key}-on` : `${s.key}-off`}
                  src={URLS[s.key]}
                  loop
                  autoplay={isActive}
                  style={{ width: '100%' }}
                />
              </div>
            );
          })}
        </div>

        {/* ── Column — step number + title + desc ─────────── */}
        <div style={{
          position: 'absolute',
          [isRTL ? 'right' : 'left']: 'clamp(32px, 6vw, 88px)',
          [isRTL ? 'left' : 'right']: 'auto',
          bottom: 'clamp(64px, 10vh, 108px)',
          zIndex: 10,
          maxWidth: 'clamp(200px, 30vw, 340px)',
          textAlign: isRTL ? 'right' : 'left',
        }}>

          {/* Step number + tag */}
          <div style={{
            display: 'flex', alignItems: 'center', gap: 10,
            marginBottom: 18,
            flexDirection: isRTL ? 'row-reverse' : 'row',
          }}>
            <span style={{
              fontSize: 11, fontWeight: 700, letterSpacing: '0.14em',
              color: ACCENT,
            }}>
              {scene.num}
            </span>
            <span style={{
              width: 20, height: 1, background: `${ACCENT}66`,
              display: 'inline-block', flexShrink: 0,
            }} />
            <span style={{
              fontSize: 10, fontWeight: 600, letterSpacing: '0.12em',
              color: 'rgba(26,22,20,0.35)',
              textTransform: 'uppercase',
            }}>
              {scene.tag}
            </span>
          </div>

          {/* Title — crossfade per scene */}
          <div style={{ position: 'relative', minHeight: 'clamp(70px, 9vh, 110px)' }}>
            {scenes.map((s, i) => {
              const isActive = sceneIdx === i;
              return (
                <h2 key={s.key} style={{
                  position: 'absolute', top: 0,
                  [isRTL ? 'right' : 'left']: 0,
                  [isRTL ? 'left' : 'right']: 'auto',
                  fontSize: 'clamp(28px, 3.6vw, 48px)',
                  fontWeight: 800,
                  letterSpacing: '-0.045em',
                  lineHeight: 1.08,
                  color: INK,
                  margin: 0,
                  whiteSpace: 'pre-line',
                  opacity:   isActive ? 1 : 0,
                  transform: `translateY(${isActive ? 0 : 10}px)`,
                  transition: 'opacity 0.45s ease, transform 0.45s ease',
                  pointerEvents: 'none',
                }}>
                  {s.title}
                </h2>
              );
            })}
          </div>

          {/* Description */}
          <div style={{ position: 'relative', marginTop: 14, minHeight: 44 }}>
            {scenes.map((s, i) => {
              const isActive = sceneIdx === i;
              return (
                <p key={s.key} style={{
                  position: 'absolute', top: 0,
                  [isRTL ? 'right' : 'left']: 0,
                  [isRTL ? 'left' : 'right']: 'auto',
                  fontSize: 12, lineHeight: 1.75,
                  color: 'rgba(26,22,20,0.42)',
                  margin: 0, maxWidth: 280,
                  opacity:   isActive ? 1 : 0,
                  transition: 'opacity 0.45s ease 0.05s',
                  pointerEvents: 'none',
                }}>
                  {s.desc}
                </p>
              );
            })}
          </div>
        </div>

        {/* ── Vertical step marks ─────────────────── */}
        <div style={{
          position: 'absolute',
          [isRTL ? 'left' : 'right']: 'clamp(28px, 4vw, 56px)',
          [isRTL ? 'right' : 'left']: 'auto',
          top: '50%', transform: 'translateY(-50%)',
          zIndex: 10,
          display: 'flex', flexDirection: 'column',
          alignItems: 'center', gap: 0,
        }}>
          {/* Track */}
          <div style={{
            position: 'relative',
            width: 1, height: 96,
            background: 'rgba(26,22,20,0.08)',
          }}>
            {/* Fill */}
            <div style={{
              position: 'absolute', top: 0, left: 0, width: '100%',
              height: `${prog * 100}%`,
              background: ACCENT,
              transition: 'height 0.1s linear',
            }} />
            {/* Dots at each step */}
            {scenes.map((s, i) => {
              const done = sceneIdx > i;
              const active = sceneIdx === i;
              return (
                <div key={s.key} style={{
                  position: 'absolute',
                  top: `${(i / (scenes.length - 1)) * 100}%`,
                  left: '50%',
                  transform: 'translate(-50%, -50%)',
                  width:  active ? 8 : 5,
                  height: active ? 8 : 5,
                  borderRadius: '50%',
                  background: active || done ? ACCENT : 'rgba(26,22,20,0.15)',
                  boxShadow: active ? `0 0 0 3px ${ACCENT}33` : 'none',
                  transition: 'all 0.35s ease',
                }} />
              );
            })}
          </div>
          {/* Step labels */}
          <div style={{
            position: 'absolute',
            top: 0,
            [isRTL ? 'left' : 'right']: 14,
            [isRTL ? 'right' : 'left']: 'auto',
            height: 96,
            display: 'flex', flexDirection: 'column',
            justifyContent: 'space-between',
            alignItems: isRTL ? 'flex-start' : 'flex-end',
          }}>
            {scenes.map((s, i) => (
              <span key={s.key} style={{
                fontSize: 9, fontWeight: 700,
                letterSpacing: '0.1em',
                color: sceneIdx === i ? ACCENT : 'rgba(26,22,20,0.2)',
                transition: 'color 0.35s',
              }}>
                {s.num}
              </span>
            ))}
          </div>
        </div>

        {/* ── Bottom thin progress line ─────────────────────────── */}
        <div style={{
          position: 'absolute', bottom: 0, left: 0, right: 0,
          height: 2, zIndex: 10,
          background: 'rgba(26,22,20,0.06)',
        }}>
          <div style={{
            height: '100%',
            width: `${prog * 100}%`,
            background: `linear-gradient(to right, ${ACCENT}88, ${ACCENT})`,
            transition: 'width 0.08s linear',
          }} />
        </div>

      </div>
    </section>
  );
}
