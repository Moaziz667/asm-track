import { IconPlus } from '@tabler/icons-react';
import { cn } from '@/lib/utils';

interface AddButtonProps {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  className?: string;
}

export function AddButton({ label, onClick, disabled, className }: AddButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cn(
        'h-7 px-3 flex items-center gap-1.5 text-[11px] font-bold rounded-md transition-colors hover:opacity-90 disabled:opacity-50 shrink-0',
        'text-white dark:text-[#121212]',
        className
      )}
      style={{ background: 'var(--brand)', border: 'none' }}
    >
      <IconPlus size={13} strokeWidth={2.5} />
      {label}
    </button>
  );
}
