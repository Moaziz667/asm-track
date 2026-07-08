import { useEffect, useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field';
import { DriverAvatar } from '@/components/data-display/DriverAvatar';
import type { Driver } from '@/types';
import type { useT } from '@/lib/i18n/LocaleContext';
import { applyFieldError } from '@/lib/utils/form-errors';
import type { DriverCrud } from './constants';

interface Props {
  open: boolean;
  onClose: () => void;
  editingDriver: Driver | null;
  onSubmit: (data: DriverCrud) => void | Promise<void>;
  saving: boolean;
  t: ReturnType<typeof useT>;
}

export function DriverFormModal({ open, onClose, editingDriver, onSubmit, saving, t }: Props) {
  const schema = useMemo(() => z.object({
    name: z.string().trim().min(1, t.validation.required),
    phone: z.string().trim().min(1, t.validation.required),
    email: z.string().trim().min(1, t.validation.required).email(t.validation.invalidEmail),
  }), [t]);

  type FormT = z.infer<typeof schema>;

  const { register, handleSubmit, reset, setError, formState: { errors } } = useForm<FormT>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', phone: '', email: '' },
  });

  // Rehydrate on open (create → blank, edit → driver values).
  useEffect(() => {
    if (!open) return;
    reset(editingDriver
      ? { name: editingDriver.name, phone: editingDriver.phone, email: editingDriver.email || '' }
      : { name: '', phone: '', email: '' });
  }, [open, editingDriver, reset]);

  const submit = handleSubmit(async (data) => {
    try {
      await onSubmit(editingDriver ? { id: editingDriver.id, ...data } : data);
    } catch (err) {
      // Surface a taken email/phone under the field (the generic toast is suppressed for these codes).
      applyFieldError(err, setError, {
        DRIVER_EMAIL_EXISTS: { field: 'email', message: t.validation.emailTaken },
        EMAIL_INVALID: { field: 'email', message: t.validation.invalidEmail },
        DRIVER_PHONE_EXISTS: { field: 'phone', message: t.validation.phoneTaken },
      });
    }
  });

  return (
    <AppModal
      open={open}
      onClose={onClose}
      title={editingDriver ? t.driversPage.editModalTitle : t.driversPage.newDriverModalTitle}
      subtitle={editingDriver ? `ID: ${editingDriver.id.slice(0, 12)}` : t.driversPage.formInstructionLabel}
      size="sm"
      footer={
        <div className="flex items-center justify-end gap-2 w-full mt-2">
          <Button variant="ghost" size="sm" className="h-7 px-3 text-xs font-bold rounded-md" onClick={onClose}>{t.driversPage.cancelButton}</Button>
          <Button
            size="sm"
            onClick={submit}
            disabled={saving}
            className="h-7 px-3 text-xs font-bold rounded-md bg-[var(--brand)] hover:opacity-90 text-white border-none"
          >
            {saving ? t.driversPage.resendInProgress : editingDriver ? t.driversPage.saveButton : t.driversPage.createButton}
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-4 mt-2 text-start">
        {editingDriver && (
          <div className="flex items-center gap-3 pb-1">
            <DriverAvatar name={editingDriver.name} photoUrl={editingDriver.photoUrl} size={48} />
            <div className="min-w-0">
              <p className="text-sm font-bold text-[var(--text-primary)] truncate">{editingDriver.name}</p>
              <p className="text-2xs text-[var(--text-muted)]">{t.driversPage.photoManagedByDriver ?? 'Photo gérée par le chauffeur (app mobile)'}</p>
            </div>
          </div>
        )}
        <FieldInput label={t.driversPage.nameLabel} placeholder={t.driversPage.namePlaceholder} required {...register('name')} error={errors.name?.message} />
        <FieldInput label={t.driversPage.phoneLabel} placeholder={t.driversPage.phonePlaceholder} required className="font-mono" {...register('phone')} error={errors.phone?.message} />
        <FieldInput label={t.driversPage.emailLabel} placeholder={t.driversPage.emailPlaceholder} type="email" required {...register('email')} error={errors.email?.message} hint={t.driversPage.invitationEmailHelp} />
      </div>
    </AppModal>
  );
}
