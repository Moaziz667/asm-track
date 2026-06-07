
import { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { Area, AreaChart, ResponsiveContainer } from 'recharts';

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

const TONE_CHART_COLOR: Record<string, string> = {
  default: 'var(--brand)',
  success: '#4CAF82',
  warning: '#D4772C',
  danger: '#C7372F',
  info: '#0972d3',
};

export function KPICard({ label, value, sub, icon, tone = 'default', sparklineData, className, onClick }: KPICardProps) {
  const chartData = sparklineData?.map((v, i) => ({ value: v, index: i }));

  return (
    <div
      onClick={onClick}
      className={cn(
        'card py-4 px-6 flex flex-col justify-center gap-2 relative overflow-hidden shadow-sm',
        onClick && 'cursor-pointer hover:border-[var(--border-strong)] transition-colors',
        className,
      )}
    >
      <div className={cn("absolute top-0 left-0 right-0 h-[3px] z-10", tone === 'default' ? 'bg-[var(--brand)]' : TONE_DOT[tone])} />
      
      {chartData && chartData.length > 0 && (
        <div className="absolute bottom-0 right-0 left-0 h-[45px] opacity-20 pointer-events-none">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={chartData}>
              <Area 
                type="monotone" 
                dataKey="value" 
                stroke={TONE_CHART_COLOR[tone] || 'var(--brand)'} 
                fill={TONE_CHART_COLOR[tone] || 'var(--brand)'} 
                strokeWidth={2} 
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}

      <div className="flex items-center justify-between relative z-10">
        <span className="label-sm text-[var(--text-muted)]">{label}</span>
        <div className="flex items-center gap-2">
          {icon && <span className="text-[var(--text-soft)]">{icon}</span>}
          <span className={cn('w-2 h-2 rounded-full', TONE_DOT[tone])} />
        </div>
      </div>
      <div className="kpi-value text-[32px] font-black leading-none text-[var(--text-primary)] tabular-nums tracking-tight mt-1 relative z-10 text-left rtl:text-right" dir="ltr">{value}</div>
      {sub && <div className="text-xs text-[var(--text-soft)] font-medium relative z-10 text-left rtl:text-right" dir="ltr">{sub}</div>}
    </div>
  );
}

