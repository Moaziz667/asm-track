import { getCurrentRole, canManageSettings } from '@/lib/auth';
import FailureReasonsTable from './failure-reasons/FailureReasonsTable';

// Promoted out of Settings into the Livraisons section: failure reasons are an
// operational catalog (what drivers pick when a delivery fails), so they belong
// next to deliveries rather than buried in admin settings.
// Breadcrumb (Deliveries › Failure reasons) is derived automatically by TopNav from
// GROUP_DEFS — no usePageBreadcrumb here, or it would append a duplicate crumb.
export default function FailureReasonsPage() {
  const canManage = canManageSettings(getCurrentRole());

  return (
    <div className="w-full h-full overflow-y-auto bg-[var(--app-bg)]">
      <div className="max-w-[1100px] mx-auto px-6 py-6">
        <FailureReasonsTable canManage={canManage} />
      </div>
    </div>
  );
}
