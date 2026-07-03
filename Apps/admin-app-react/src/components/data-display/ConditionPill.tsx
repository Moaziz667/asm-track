import { IconRecycle, IconTrashX } from '@tabler/icons-react';

export type Condition = 'RESELLABLE' | 'DAMAGED';

interface ConditionConfig {
  dot: string;
  bg: string;
  text: string;
}

const CONFIG: Record<Condition, ConditionConfig> = {
  RESELLABLE: { dot: 'var(--success)', bg: 'var(--success-bg)', text: 'var(--success)' },
  DAMAGED:    { dot: 'var(--danger)',  bg: 'var(--danger-bg)',  text: 'var(--danger)' },
};

const ICON: Record<Condition, typeof IconRecycle> = {
  RESELLABLE: IconRecycle,
  DAMAGED: IconTrashX,
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
  const Icon = ICON[condition];
  const iconPx = size === 'sm' ? 13 : 14;
  const height = size === 'sm' ? 18 : 20;
  const px = size === 'sm' ? 7 : 8;

  return (
    <span
      className="inline-flex items-center shrink-0 rounded-full border"
      style={{
        gap: 5,
        height,
        padding: `0 ${px}px`,
        background: cfg.bg,
        color: cfg.text,
        borderColor: `color-mix(in srgb, ${cfg.dot} 18%, transparent)`,
      }}
    >
      <Icon size={iconPx} color={cfg.dot} stroke={1.9} />
      <span className="text-2xs font-medium whitespace-nowrap">
        {label ?? condition}
      </span>
    </span>
  );
}
