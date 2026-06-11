import { IconPlus } from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

interface AddButtonProps {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  className?: string;
}

/**
 * Primary "create" button used across list pages (New route / New vehicle / …).
 * Thin wrapper over the shared <Button> so it inherits the exact same typography, sizing,
 * focus/hover/active states and brand color as every other primary button (e.g. "Add reason").
 * Keeping it as a wrapper avoids style drift between the two.
 */
export function AddButton({ label, onClick, disabled, className }: AddButtonProps) {
  return (
    <Button size="sm" onClick={onClick} disabled={disabled} className={cn('gap-1.5', className)}>
      <IconPlus size={14} />
      {label}
    </Button>
  );
}
