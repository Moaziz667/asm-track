
import { forwardRef, useId, InputHTMLAttributes, TextareaHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/utils';

// ── Shared label + error wrapper ──────────────────────────────────────────────
// Accessibility: the wrapper owns the ids so the <label htmlFor> always points at its control,
// and error/hint text is linked via aria-describedby. It hands those ids to its child control
// through a render prop, so every Field* gets correct a11y wiring for free.

interface FieldA11y {
  controlId: string;
  describedBy?: string;   // id(s) of the error/hint text, for aria-describedby
  invalid: boolean;
}

interface FieldWrapperProps {
  label?: ReactNode;
  error?: string;
  hint?: string;
  required?: boolean;
  /** Render prop: receives the generated ids to spread onto the control. */
  children: (a11y: FieldA11y) => ReactNode;
  className?: string;
  id?: string;
}

export function FieldWrapper({ label, error, hint, required, children, className, id }: FieldWrapperProps) {
  const autoId = useId();
  const controlId = id ?? autoId;
  const errorId = error ? `${controlId}-error` : undefined;
  const hintId = hint && !error ? `${controlId}-hint` : undefined;
  const describedBy = errorId ?? hintId;

  return (
    <div className={cn('flex flex-col gap-1', className)}>
      {label && (
        <label htmlFor={controlId} className="text-xs font-medium text-[var(--text-muted)] select-none">
          {label}
          {required && <span className="text-[var(--danger)] ml-0.5" aria-hidden="true">*</span>}
        </label>
      )}
      {children({ controlId, describedBy, invalid: !!error })}
      {error && <p id={errorId} className="text-xs text-[var(--danger)]" role="alert">{error}</p>}
      {hint && !error && <p id={hintId} className="text-xs text-[var(--text-soft)]">{hint}</p>}
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
      <FieldWrapper label={label} error={error} hint={hint} required={required} className={wrapperClassName} id={props.id}>
        {({ controlId, describedBy, invalid }) => (
        <div className="relative flex items-center">
          {leftSection && (
            <div className="absolute left-3 flex items-center text-[var(--text-soft)] pointer-events-none">
              {leftSection}
            </div>
          )}
          <input
            ref={ref}
            id={controlId}
            aria-invalid={invalid || undefined}
            aria-describedby={describedBy}
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
        )}
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
      <FieldWrapper label={label} error={error} hint={hint} required={required} className={wrapperClassName} id={props.id}>
        {({ controlId, describedBy, invalid }) => (
        <textarea
          ref={ref}
          id={controlId}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          rows={3}
          className={cn(
            inputBase,
            'resize-none min-h-[80px]',
            error && inputError,
            className,
          )}
          {...props}
        />
        )}
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
      <FieldWrapper label={label} error={error} hint={hint} required={required} className={wrapperClassName} id={props.id}>
        {({ controlId, describedBy, invalid }) => (
        <div className="relative">
          <select
            ref={ref}
            id={controlId}
            aria-invalid={invalid || undefined}
            aria-describedby={describedBy}
            value={value}
            onChange={onChange as React.ChangeEventHandler<HTMLSelectElement>}
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
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" aria-hidden="true">
              <path d="m6 9 6 6 6-6"/>
            </svg>
          </div>
        </div>
        )}
      </FieldWrapper>
    );
  }
);
FieldSelect.displayName = 'FieldSelect';

