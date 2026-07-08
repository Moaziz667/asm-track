'use client';

import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/i18n/LocaleContext';
import { IconTrash, IconAlertTriangle } from '@tabler/icons-react';
import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { useRouteBuilderContext } from '../../hooks/useRouteBuilder';

export function DeleteModal() {
  const t = useT();
  const locale = useLocaleStore(state => state.locale);
  const rb = useRouteBuilderContext();
  const {
    confirmDeleteRouteId,
    setConfirmDeleteRouteId,
    confirmDeleteDraftRoute,
  } = rb;

  return (
    <AppModal
      opened={!!confirmDeleteRouteId}
      onClose={() => setConfirmDeleteRouteId(null)}
      title={t.routeBuilderPage.deleteDraftModalTitle}
      subtitle={t.routeBuilderPage.deleteConfirmTitle}
      variant="danger"
      size="sm"
      footer={
        <div className="flex items-center justify-end gap-2 w-full">
          <Button variant="outline" size="sm" onClick={() => setConfirmDeleteRouteId(null)}>
            {t.actions.cancel}
          </Button>
          <Button
            size="sm"
            variant="destructive"
            onClick={() => void confirmDeleteDraftRoute()}
            className="font-semibold"
          >
            <IconAlertTriangle size={14} className="mr-1.5" />
            {t.actions.delete}
          </Button>
        </div>
      }
    >
      <div className="flex flex-col items-center gap-4 py-2">
        <div className="w-14 h-14 rounded-full bg-destructive/10 text-destructive flex items-center justify-center dark:bg-destructive/20">
          <IconTrash size={24} />
        </div>
        <p className="text-xs text-[var(--text-secondary)] text-center max-w-[280px] leading-relaxed">
          {t.routeBuilderPage.deleteDraftModalBody}
        </p>
      </div>
    </AppModal>
  );
}
