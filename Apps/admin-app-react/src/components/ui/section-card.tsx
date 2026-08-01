
import { ReactNode } from 'react';
import { cn } from '@/lib/utils';

interface SectionCardProps {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  padding?: boolean;
  className?: string;
  contentClassName?: string;
}

export function SectionCard({
  title,
  subtitle,
  actions,
  children,
  padding = true,
  className,
  contentClassName,
}: SectionCardProps) {
  return (
    /* A hairline on the app background is not a surface — it reads as an absence, which is why a
       page of these felt inert. DESIGN.md already specifies "container = 1px border + faint
       shadow"; the card had simply never applied it. The hover step is feedback rather than
       decoration: most places these appear, the card leads somewhere. */
    <div
      className={cn(
        'border border-[var(--border)] rounded-lg bg-[var(--surface)]',
        'shadow-[var(--shadow-card)] transition-shadow duration-200',
        'hover:shadow-[var(--shadow-card-hover)]',
        className,
      )}
    >
      {(title || actions) && (
        <div className="flex items-start justify-between gap-3 pl-10 pr-4 py-3 border-b border-[var(--border)]">
          <div>
            {title && (
              <h3 className="section-title">{title}</h3>
            )}
            {subtitle && (
              <p className="text-xs text-[var(--text-soft)] mt-0.5">{subtitle}</p>
            )}
          </div>
          {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
        </div>
      )}
      <div className={cn(padding && 'p-4', contentClassName)}>
        {children}
      </div>
    </div>
  );
}

