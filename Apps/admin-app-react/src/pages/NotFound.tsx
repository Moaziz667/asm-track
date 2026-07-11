import { Link } from 'react-router-dom';
import { IconCompass, IconArrowLeft } from '@tabler/icons-react';

export default function NotFound() {
  return (
    <div className="flex h-full min-h-[60vh] flex-col items-center justify-center gap-5 px-6 text-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <IconCompass size={30} className="text-[var(--text-muted)]" />
      </div>
      <div className="flex flex-col gap-1">
        <span className="text-xs font-bold uppercase tracking-[0.18em] text-[var(--text-muted)]">ERROR 404</span>
        <h1 className="text-2xl font-semibold tracking-tight text-[var(--text-primary)]">Page not found</h1>
        <p className="mt-1 max-w-md text-sm text-[var(--text-muted)]">This page does not exist or has been moved.</p>
      </div>
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
