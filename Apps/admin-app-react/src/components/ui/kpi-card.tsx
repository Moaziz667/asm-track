
import { ReactNode } from 'react';
import { IconTrendingUp, IconTrendingDown, IconMinus } from '@tabler/icons-react';
import { cn } from '@/lib/utils';

/** A direction-aware trend chip. `delta` drives the arrow; `goodWhen` decides red vs green so
 *  "failures up" reads red while "delivered up" reads green. Caption is the "vs prev" line. */
export interface KpiTrend {
  delta: number;                 // signed change (e.g. +12 or -8)
  format: (n: number) => string; // how to render it, e.g. n => `${n.toFixed(0)}%`
  goodWhen?: 'up' | 'down';      // which direction is positive (default 'up')
  caption?: string;              // e.g. "vs prev. 7j"
}

interface KPICardProps {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  trend?: KpiTrend;
  icon?: ReactNode;
  tone?: 'default' | 'success' | 'warning' | 'danger' | 'info';
  className?: string;
  onClick?: () => void;
  sparklineData?: number[];
}

const TONE_DOT: Record<string, string> = {
  default: 'bg-[var(--text-soft)]',
  success: 'bg-[var(--success)]',
  warning: 'bg-[var(--warning)]',
  danger:  'bg-[var(--danger)]',
  info:    'bg-[var(--info)]',
};

function TrendPill({ trend }: { trend: KpiTrend }) {
  const goodUp = (trend.goodWhen ?? 'up') === 'up';
  const isUp = trend.delta > 0;
  const isFlat = trend.delta === 0;
  // "Good" = moving in the desired direction. Flat is neutral.
  const isGood = isFlat ? null : (isUp === goodUp);
  const color = isGood === null ? 'var(--text-soft)' : isGood ? 'var(--success)' : 'var(--danger)';
  const Icon = isFlat ? IconMinus : isUp ? IconTrendingUp : IconTrendingDown;
  return (
    <span className="inline-flex items-center gap-1 text-xs font-[600]" style={{ color }}>
      <Icon size={13} stroke={2.2} />
      {trend.delta > 0 ? '+' : ''}{trend.format(trend.delta)}
      {trend.caption && <span className="font-normal text-[var(--text-soft)]">{trend.caption}</span>}
    </span>
  );
}

export function KPICard({ label, value, sub, trend, icon, tone = 'default', className, onClick, sparklineData }: KPICardProps) {
  const renderSparkline = () => {
    if (!sparklineData || sparklineData.length < 2) return null;
    const max = Math.max(...sparklineData);
    const min = Math.min(...sparklineData);
    const range = max - min === 0 ? 1 : max - min;
    const width = 100;
    const height = 24;
    const points = sparklineData.map((val, index) => {
      const x = (index / (sparklineData.length - 1)) * width;
      const y = height - ((val - min) / range) * (height - 4) - 2;
      return `${x},${y}`;
    }).join(' ');

    const strokeColor = tone === 'success' ? 'var(--success)' : tone === 'warning' ? 'var(--warning)' : tone === 'danger' ? 'var(--danger)' : tone === 'info' ? 'var(--info)' : 'var(--brand)';

    return (
      <div className="absolute bottom-2 right-4 opacity-60 pointer-events-none">
        <svg width={width} height={height}>
          <polyline
            fill="none"
            stroke={strokeColor}
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            points={points}
          />
        </svg>
      </div>
    );
  };

  return (
    <div
      onClick={onClick}
      className={cn(
        'card pl-10 pr-4 py-4 flex flex-col justify-center h-full relative overflow-hidden',
        onClick && 'cursor-pointer',
        className,
      )}
    >
      {tone !== 'default' && (
        <div className={cn('absolute top-0 left-0 right-0 h-[3px]', TONE_DOT[tone])} />
      )}
      <div className="flex items-start justify-between mb-3 shrink-0 relative z-10">
        <span className="text-sm font-medium text-[var(--text-muted)]">{label}</span>
        <div className="flex items-center gap-2 text-[var(--text-muted)]">
          {icon}
          {tone !== 'default' && <span className={cn('w-2 h-2 rounded-full', TONE_DOT[tone])} />}
        </div>
      </div>
      <div className="font-mono text-[2.5rem] font-bold leading-none tabular-nums text-[var(--text-primary)] relative z-10 text-left rtl:text-right" dir="ltr">
        {value}
      </div>
      {trend ? (
        <div className="mt-1.5 relative z-10 text-left rtl:text-right" dir="ltr">
          <TrendPill trend={trend} />
        </div>
      ) : sub ? (
        <div className="text-xs text-[var(--text-muted)] mt-1.5 font-normal relative z-10 text-left rtl:text-right" dir="ltr">
          {sub}
        </div>
      ) : null}
      {renderSparkline()}
    </div>
  );
}

