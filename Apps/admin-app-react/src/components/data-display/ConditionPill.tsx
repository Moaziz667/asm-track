export type Condition = 'RESELLABLE' | 'DAMAGED';

interface ConditionConfig {
  dot: string;
  bg: string;
  text: string;
}

const CONFIG: Record<Condition, ConditionConfig> = {
  RESELLABLE: { dot: '#2A8F8F', bg: 'rgba(42,143,143,0.09)', text: '#1E7070' },
  DAMAGED:    { dot: '#C2506A', bg: 'rgba(194,80,106,0.09)', text: '#A43E56' },
};

/** Raw colour access for toggle buttons that need active/inactive states. */
export function conditionColors(c: Condition): { dot: string; text: string; bg: string } {
  return CONFIG[c];
}

interface ConditionPillProps {
  condition: Condition;
  label?: string;
  size?: 'sm' | 'md';
}

export function ConditionPill({ condition, label, size = 'sm' }: ConditionPillProps) {
  const cfg = CONFIG[condition];
  const dotPx = size === 'sm' ? 5 : 5.5;
  const fontSize = size === 'sm' ? 11 : 11;
  const height = size === 'sm' ? 18 : 20;
  const px = size === 'sm' ? 7 : 8;

  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 5,
        height,
        padding: `0 ${px}px`,
        borderRadius: 99,
        background: cfg.bg,
        flexShrink: 0,
      }}
    >
      <span style={{ width: dotPx, height: dotPx, borderRadius: '50%', background: cfg.dot, flexShrink: 0 }} />
      <span
        style={{
          fontFamily: "'Clear Sans', system-ui, sans-serif",
          fontSize,
          fontWeight: 500,
          color: cfg.text,
          letterSpacing: '-0.01em',
          lineHeight: 1,
          whiteSpace: 'nowrap',
        }}
      >
        {label ?? condition}
      </span>
    </span>
  );
}
