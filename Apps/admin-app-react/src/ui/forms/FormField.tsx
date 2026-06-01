
import * as React from 'react';
import { cn } from '@/lib/utils';

interface FormFieldProps extends React.ComponentProps<'div'> {
  label?: string;
  error?: string;
}

export function FormField({ label, error, children, className, ...props }: FormFieldProps) {
  return (
    <div className={cn('flex flex-col gap-dense-1', className)} {...props}>
      {label && (
        <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] select-none">
          {label}
        </span>
      )}
      {children}
      {error && (
        <span className="text-[10px] font-semibold text-state-critical">
          {error}
        </span>
      )}
    </div>
  );
}

export interface InputProps extends React.ComponentProps<'input'> {
  error?: boolean;
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, error, ...props }, ref) => {
    return (
      <input
        ref={ref}
        className={cn(
          'w-full h-8 px-dense-3 rounded-dense-sm border border-[var(--border-grid)] bg-[var(--bg-panel)] text-[var(--text-primary)] text-xs font-semibold placeholder:text-[var(--text-muted)]/70 outline-none transition-all focus:border-state-active focus:ring-1 focus:ring-state-active/50 disabled:opacity-50 disabled:cursor-not-allowed',
          error && 'border-state-critical focus:border-state-critical focus:ring-state-critical/50',
          className
        )}
        {...props}
      />
    );
  }
);
Input.displayName = 'Input';

export interface SelectProps extends React.ComponentProps<'select'> {
  error?: boolean;
}

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, error, children, ...props }, ref) => {
    return (
      <select
        ref={ref}
        className={cn(
          'w-full h-8 px-dense-3 rounded-dense-sm border border-[var(--border-grid)] bg-[var(--bg-panel)] text-[var(--text-primary)] text-xs font-semibold outline-none transition-all focus:border-state-active focus:ring-1 focus:ring-state-active/50 disabled:opacity-50 disabled:cursor-not-allowed appearance-none cursor-pointer',
          error && 'border-state-critical focus:border-state-critical focus:ring-state-critical/50',
          className
        )}
        {...props}
      >
        {children}
      </select>
    );
  }
);
Select.displayName = 'Select';

export interface TextareaProps extends React.ComponentProps<'textarea'> {
  error?: boolean;
}

export const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, error, ...props }, ref) => {
    return (
      <textarea
        ref={ref}
        className={cn(
          'w-full min-h-16 px-dense-3 py-dense-2 rounded-dense-sm border border-[var(--border-grid)] bg-[var(--bg-panel)] text-[var(--text-primary)] text-xs font-semibold placeholder:text-[var(--text-muted)]/70 outline-none transition-all focus:border-state-active focus:ring-1 focus:ring-state-active/50 disabled:opacity-50 disabled:cursor-not-allowed resize-none',
          error && 'border-state-critical focus:border-state-critical focus:ring-state-critical/50',
          className
        )}
        {...props}
      />
    );
  }
);
Textarea.displayName = 'Textarea';

