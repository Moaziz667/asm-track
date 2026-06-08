
import { ReactNode } from 'react';
import { cn } from '@/lib/utils';
interface KPICardProps {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
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

export function KPICard({ label, value, sub, icon, tone = 'default', className, onClick, sparklineData }: KPICardProps) {
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
      <div className="absolute bottom-2 right-4 opacity-40 pointer-events-none">
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
      <div className="flex items-start justify-between mb-3 shrink-0 relative z-10">
        <span className="text-[12px] font-medium text-[var(--text-muted)]">{label}</span>
        <div className="flex items-center gap-2 text-[var(--text-muted)]">
          {icon}
          {tone !== 'default' && <span className={cn('w-2 h-2 rounded-full', TONE_DOT[tone])} />}
        </div>
      </div>
      <div className="font-mono text-[28px] font-semibold leading-none tabular-nums text-[var(--text-primary)] relative z-10 text-left rtl:text-right" dir="ltr">
        {value}
      </div>
      {sub && (
        <div className="text-[11px] text-[var(--text-muted)] mt-1.5 font-normal relative z-10 text-left rtl:text-right" dir="ltr">
          {sub}
        </div>
      )}
      {renderSparkline()}
    </div>
  );
}

