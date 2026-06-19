import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { useT } from '@/lib/LocaleContext';
import { getCurrentRole, canManageSettings } from '@/lib/auth';
import FailureReasonsTable from './failure-reasons/FailureReasonsTable';

// Promoted out of Settings into the Livraisons section: failure reasons are an
// operational catalog (what drivers pick when a delivery fails), so they belong
// next to deliveries rather than buried in admin settings.
export default function FailureReasonsPage() {
  const t = useT();
  const canManage = canManageSettings(getCurrentRole());
  usePageBreadcrumb([
    { label: t.sidebar?.groups?.deliveries || "Livraisons", href: '/deliveries' },
    { label: t.sidebar?.items?.failureReasons || t.settingsPage?.failureReasons || "Motifs d'échec" }
  ]);

  return (
    <div className="w-full h-full overflow-y-auto bg-[var(--app-bg)]">
      <div className="max-w-[1100px] mx-auto px-6 py-6">
        <FailureReasonsTable canManage={canManage} />
      </div>
    </div>
  );
}
