import { Link } from 'react-router-dom';
import { IconLock, IconArrowLeft } from '@tabler/icons-react';

export function Forbidden({ requiredPerm }: { requiredPerm?: string }) {
  return (
    <div className="flex h-full min-h-[60vh] flex-col items-center justify-center gap-5 px-6 text-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <IconLock size={30} className="text-[var(--text-muted)]" />
      </div>
      <div className="flex flex-col gap-1">
        <span className="text-xs font-bold uppercase tracking-[0.18em] text-[var(--text-muted)]">ERROR 403</span>
        <h1 className="text-2xl font-semibold tracking-tight text-[var(--text-primary)]">Access denied</h1>
        <p className="mt-1 max-w-md text-sm text-[var(--text-muted)]">You do not have the permission required to view this page.</p>
      </div>
      {requiredPerm && (
        <div className="flex items-center gap-2 text-xs text-[var(--text-muted)]">
          <span>Requires:</span>
          <span className="rounded-full border border-[var(--border)] bg-[var(--surface)] px-2 py-0.5 font-mono font-semibold text-[var(--text-primary)]">
            {requiredPerm}
          </span>
        </div>
      )}
      <Link
        to="/dashboard"
        className="mt-2 inline-flex items-center gap-2 rounded-md bg-[var(--brand)] px-4 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90"
      >
        <IconArrowLeft size={16} />
        Back to dashboard
      </Link>
    </div>
  );
}

export default Forbidden;
