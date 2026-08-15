import {
  IconFileText, IconBroadcast, IconGauge, IconHelpCircle, IconAlertTriangle,
} from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import type { ProvenanceKind } from './provenance';

// Tones follow StatusBadge's desaturated palette; each pairs an icon + label so status is never
// carried by color alone (see .ai/anti-slop.md and the product design principles).
const TONE: Record<ProvenanceKind, { icon: typeof IconFileText; dot: string; bg: string; text: string; pulse?: boolean }> = {
  documentation: { icon: IconFileText,      dot: '#2594B8', bg: 'rgba(37,148,184,0.10)', text: '#1A7A9A' },
  live:          { icon: IconBroadcast,     dot: '#037F0C', bg: 'rgba(3,127,12,0.10)',   text: '#037F0C', pulse: true },
  sla:           { icon: IconGauge,         dot: '#5E6AD2', bg: 'rgba(94,106,210,0.10)', text: '#4C56B8' },
  refused:       { icon: IconHelpCircle,    dot: '#8D6605', bg: 'rgba(141,102,5,0.10)',  text: '#8D6605' },
  degraded:      { icon: IconAlertTriangle, dot: '#D91515', bg: 'rgba(217,21,21,0.09)',  text: '#A52B24' },
};

export function RouteBadge({ kind }: { kind: ProvenanceKind }) {
  const t = useT();
  const tone = TONE[kind];
  const Icon = tone.icon;
  const label = t.assistant.route[kind];

  return (
    <span
      role="status"
      aria-label={label}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 5, height: 20,
        padding: '0 8px', borderRadius: 99, background: tone.bg, flexShrink: 0,
      }}
    >
      <span style={{ display: 'inline-flex', ...(tone.pulse ? { animation: 'asm-pulse 2s ease-in-out infinite' } : {}) }}>
        <Icon size={13} color={tone.dot} stroke={1.9} />
      </span>
      <span style={{
        fontFamily: 'var(--font-sans)', fontSize: 11, fontWeight: 600, color: tone.text,
        letterSpacing: '-0.01em', lineHeight: 1, whiteSpace: 'nowrap',
      }}>
        {label}
      </span>
    </span>
  );
}
