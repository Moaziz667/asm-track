import { IconCircleCheck, IconClockExclamation, IconAlertTriangle, IconCircle } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { tlabel } from '@/lib/i18n/i18n-dict';

/**
 * Compact SLA health chip for list rows — reads the backend's single (phase, health) truth.
 * No emojis; Tabler SVG icons. Renders nothing for NONE/missing so terminal rows stay clean.
 */
type Health = 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'MET' | 'LATE' | 'NONE';

const TONE: Record<string, { fg: string; bg: string; dot: string }> = {
  ok:     { fg: '#2D8A5E', bg: 'rgba(76,175,130,0.10)', dot: '#4CAF82' },
  risk:   { fg: '#B05A18', bg: 'rgba(212,119,44,0.10)',  dot: '#D4772C' },
  breach: { fg: '#A52B24', bg: 'rgba(199,55,47,0.10)',   dot: '#C7372F' },
};

function toneFor(h?: Health) {
  switch (h) {
    case 'AT_RISK': case 'LATE': return TONE.risk;
    case 'BREACHED': return TONE.breach;
    case 'MET': case 'ON_TRACK': return TONE.ok;
    default: return null;
  }
}

function IconFor({ h, color }: { h?: Health; color: string }) {
  const p = { size: 13, stroke: 1.9, color };
  if (h === 'BREACHED') return <IconAlertTriangle {...p} />;
  if (h === 'AT_RISK') return <IconClockExclamation {...p} />;
  if (h === 'LATE') return <IconClockExclamation {...p} />;
  if (h === 'MET' || h === 'ON_TRACK') return <IconCircleCheck {...p} />;
  return <IconCircle {...p} />;
}

export default function SlaHealthBadge({ health, size = 'sm' }: { health?: string; size?: 'sm' | 'md' }) {
  const t = useT();
  const h = health as Health | undefined;
  const tone = toneFor(h);
  if (!tone || !h) return null;
  const label = tlabel(t.slaTimeline?.health, h) ?? h;
  const fs = size === 'md' ? 11.5 : 10.5;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: fs, fontWeight: 600,
      color: tone.fg, background: tone.bg, border: `1px solid ${tone.dot}33`,
      borderRadius: 99, padding: '1px 7px', whiteSpace: 'nowrap',
    }}>
      <IconFor h={h} color={tone.fg} />
      {label}
    </span>
  );
}
