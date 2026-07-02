import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field';
import { DriverAvatar } from '@/components/data-display/DriverAvatar';
import type { Driver } from '@/types';
import type { useT } from '@/lib/LocaleContext';
import type { DriverCrud } from './constants';

interface Props {
  open: boolean;
  onClose: () => void;
  editingDriver: Driver | null;
  form: DriverCrud;
  setForm: (f: DriverCrud) => void;
  onSave: () => void;
  saving: boolean;
  t: ReturnType<typeof useT>;
}

export function DriverFormModal({ open, onClose, editingDriver, form, setForm, onSave, saving, t }: Props) {
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
            onClick={onSave}
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
        <FieldInput label={t.driversPage.nameLabel} placeholder={t.driversPage.namePlaceholder} required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        <FieldInput label={t.driversPage.phoneLabel} placeholder={t.driversPage.phonePlaceholder} required value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} className="font-mono" />
        <FieldInput label={t.driversPage.emailLabel} placeholder={t.driversPage.emailPlaceholder} type="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} hint={t.driversPage.invitationEmailHelp} />
      </div>
    </AppModal>
  );
}
