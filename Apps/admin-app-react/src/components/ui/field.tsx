
import { forwardRef, InputHTMLAttributes, TextareaHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/utils';

// ── Shared label + error wrapper ──────────────────────────────────────────────

interface FieldWrapperProps {
  label?: ReactNode;
  error?: string;
  hint?: string;
  required?: boolean;
  children: ReactNode;
  className?: string;
}

export function FieldWrapper({ label, error, hint, required, children, className }: FieldWrapperProps) {
  return (
    <div className={cn('flex flex-col gap-1', className)}>
      {label && (
        <label className="text-xs font-medium text-[var(--text-muted)] select-none">
          {label}
          {required && <span className="text-[var(--danger)] ml-0.5">*</span>}
        </label>
      )}
      {children}
      {error && <p className="text-[11px] text-[var(--danger)]">{error}</p>}
      {hint && !error && <p className="text-[11px] text-[var(--text-soft)]">{hint}</p>}
    </div>
  );
}

// ── Base input style ──────────────────────────────────────────────────────────

const inputBase = [
  'w-full rounded bg-[var(--surface)] border border-[var(--border)]',
  'text-sm text-[var(--text-primary)] placeholder:text-[var(--text-soft)]',
  'px-3 py-2 leading-tight',
  'transition-colors duration-100',
  'focus:outline-none focus:border-[var(--brand)] focus:ring-1 focus:ring-[var(--brand)]',
  'disabled:opacity-50 disabled:cursor-not-allowed',
].join(' ');

const inputError = 'border-[var(--danger)] focus:border-[var(--danger)] focus:ring-[var(--danger)]';

// ── FieldInput ────────────────────────────────────────────────────────────────

interface FieldInputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: ReactNode;
  error?: string;
  hint?: string;
  leftSection?: ReactNode;
  rightSection?: ReactNode;
  wrapperClassName?: string;
}

export const FieldInput = forwardRef<HTMLInputElement, FieldInputProps>(
  ({ label, error, hint, required, leftSection, rightSection, className, wrapperClassName, ...props }, ref) => {
    return (
      <FieldWrapper label={label} error={error} hint={hint} required={required} className={wrapperClassName}>
        <div className="relative flex items-center">
          {leftSection && (
            <div className="absolute left-3 flex items-center text-[var(--text-soft)] pointer-events-none">
              {leftSection}
            </div>
          )}
          <input
            ref={ref}
            className={cn(
              inputBase,
              error && inputError,
              leftSection && 'pl-9',
              rightSection && 'pr-9',
              className,
            )}
            {...props}
          />
          {rightSection && (
            <div className="absolute right-3 flex items-center text-[var(--text-soft)]">
              {rightSection}
            </div>
          )}
        </div>
      </FieldWrapper>
    );
  }
);
FieldInput.displayName = 'FieldInput';

// ── FieldTextarea ─────────────────────────────────────────────────────────────

interface FieldTextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: ReactNode;
  error?: string;
  hint?: string;
  wrapperClassName?: string;
}

export const FieldTextarea = forwardRef<HTMLTextAreaElement, FieldTextareaProps>(
  ({ label, error, hint, required, className, wrapperClassName, ...props }, ref) => {
    return (
      <FieldWrapper label={label} error={error} hint={hint} required={required} className={wrapperClassName}>
        <textarea
          ref={ref}
          rows={3}
          className={cn(
            inputBase,
            'resize-none min-h-[80px]',
            error && inputError,
            className,
          )}
          {...props}
        />
      </FieldWrapper>
    );
  }
);
FieldTextarea.displayName = 'FieldTextarea';

// ── FieldSelect ───────────────────────────────────────────────────────────────

interface FieldSelectProps extends InputHTMLAttributes<HTMLSelectElement> {
  label?: ReactNode;
  error?: string;
  hint?: string;
  options: { value: string; label: string; disabled?: boolean }[];
  placeholder?: string;
  wrapperClassName?: string;
}

export const FieldSelect = forwardRef<HTMLSelectElement, FieldSelectProps>(
  ({ label, error, hint, required, options, placeholder, className, wrapperClassName, value, onChange, ...props }, ref) => {
    return (
      <FieldWrapper label={label} error={error} hint={hint} required={required} className={wrapperClassName}>
        <div className="relative">
          <select
            ref={ref}
            value={value}
            onChange={onChange as any}
            className={cn(
              inputBase,
              'appearance-none pr-8 cursor-pointer',
              error && inputError,
              className,
            )}
            {...props}
          >
            {placeholder && <option value="">{placeholder}</option>}
            {options.map(o => (
              <option key={o.value} value={o.value} disabled={o.disabled}>{o.label}</option>
            ))}
          </select>
          <div className="absolute right-3 top-1/2 -translate-y-1/2 pointer-events-none text-[var(--text-soft)]">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="m6 9 6 6 6-6"/>
            </svg>
          </div>
        </div>
      </FieldWrapper>
    );
  }
);
FieldSelect.displayName = 'FieldSelect';

