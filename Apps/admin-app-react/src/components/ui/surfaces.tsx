import React from 'react';

type SurfaceCardProps = {
  children: React.ReactNode;
  style?: React.CSSProperties;
  className?: string;
  onClick?: (e: React.MouseEvent) => void;
};

export function SurfaceCard({ children, style, className, onClick }: SurfaceCardProps) {
  return (
    <div
      className={className}
      onClick={onClick}
      style={{
        background: 'var(--card, #ffffff)',
        border: 'none',
        borderRadius: 6,
        boxShadow: '0 8px 24px rgba(42,52,57,0.04)',
        ...style,
      }}
    >
      {children}
    </div>
  );
}

type PanelProps = {
  title?: React.ReactNode;
  subtitle?: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
  style?: React.CSSProperties;
  contentStyle?: React.CSSProperties;
};

export function Panel({ title, subtitle, actions, children, style, contentStyle }: PanelProps) {
  return (
    <SurfaceCard style={{ padding: 16, ...style }}>
      {(title || subtitle || actions) && (
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, marginBottom: 12 }}>
          <div>
            {title ? <div className="section-title">{title}</div> : null}
            {subtitle ? <div style={{ marginTop: 4, fontSize: 12, color: 'var(--muted-foreground, #566166)' }}>{subtitle}</div> : null}
          </div>
          {actions ? <div>{actions}</div> : null}
        </div>
      )}
      <div style={contentStyle}>{children}</div>
    </SurfaceCard>
  );
}

type MetricCardProps = {
  label: string;
  value: React.ReactNode;
  tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'info';
  icon?: React.ReactNode;
  footer?: React.ReactNode;
  style?: React.CSSProperties;
};

const toneColor: Record<NonNullable<MetricCardProps['tone']>, { text: string; bg: string }> = {
  neutral: { text: '#515f74', bg: 'rgba(81,95,116,0.1)' },
  success: { text: '#15803d', bg: 'rgba(21,128,61,0.1)' },
  warning: { text: '#b45309', bg: 'rgba(180,83,9,0.1)' },
  danger: { text: '#b91c1c', bg: 'rgba(185,28,28,0.1)' },
  info: { text: '#1d4ed8', bg: 'rgba(29,78,216,0.1)' },
};

export function MetricCard({ label, value, tone = 'neutral', icon, footer, style }: MetricCardProps) {
  const color = toneColor[tone];
  return (
    <SurfaceCard
      style={{
        padding: 16,
        background: 'linear-gradient(180deg, #ffffff 0%, #fbfcfd 100%)',
        ...style,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <span className="section-label">{label}</span>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          {icon ? <span style={{ color: color.text }}>{icon}</span> : null}
          <span
            aria-hidden
            style={{
              width: 8,
              height: 8,
              borderRadius: 999,
              background: color.bg,
              border: '1px solid rgba(169,180,185,0.15)',
            }}
          />
        </div>
      </div>
      <div className="metric-value" style={{ color: 'var(--foreground, #2a3439)' }}>{value}</div>
      {footer ? <div style={{ marginTop: 8, fontSize: 12, color: 'var(--muted-foreground, #566166)' }}>{footer}</div> : null}
    </SurfaceCard>
  );
}

