
import React from 'react';
import { Button } from '@/components/ui/button';
import { tw } from '@/lib/ui/typography';

interface EmptyStateProps {
  icon?: React.ReactNode;
  message: string;
  hint?: string;
  action?: {
    label: string;
    onClick: () => void;
  };
}

export function EmptyState({ icon, message, hint, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-12 px-6 gap-3 text-center">
      {icon && (
        <div className="text-[var(--text-muted)] mb-1 opacity-40">
          {icon}
        </div>
      )}
      <p className={`${tw.body} font-semibold text-[var(--text-muted)] max-w-xs`}>
        {message}
      </p>
      {hint && (
        <p className={`${tw.caption} text-[var(--text-muted)] max-w-xs leading-relaxed opacity-70`}>
          {hint}
        </p>
      )}
      {action && (
        <Button size="sm" onClick={action.onClick} className="mt-1">
          {action.label}
        </Button>
      )}
    </div>
  );
}

