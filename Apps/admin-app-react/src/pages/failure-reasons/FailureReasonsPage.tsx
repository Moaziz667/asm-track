import { hasPerm } from '@/lib/api/auth';
import FailureReasonsTable from './FailureReasonsTable';

// Promoted out of Settings into the Livraisons section: failure reasons are an
// operational catalog (what drivers pick when a delivery fails), so they belong
// next to deliveries rather than buried in admin settings.
// Breadcrumb (Deliveries › Failure reasons) is derived automatically by TopNav from
// GROUP_DEFS — no usePageBreadcrumb here, or it would append a duplicate crumb.
export default function FailureReasonsPage() {
  const canManage = hasPerm('perm:settings:manage');

  // The table now owns the full-height admin shell (PageFilterBar + toolbar + paginated table),
  // identical to Drivers / Vehicles / Deliveries — so this page just renders it edge-to-edge.
  return <FailureReasonsTable canManage={canManage} />;
}
