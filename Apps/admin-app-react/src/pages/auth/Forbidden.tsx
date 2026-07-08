import { Link } from 'react-router-dom';
import { IconLock, IconArrowLeft } from '@tabler/icons-react';
import { AdminRole } from '@/lib/api/auth';
import { useLocaleStore } from '@/lib/i18n';

const COPY = {
  fr: {
    code: 'ERREUR 403',
    title: 'Accès refusé',
    body: 'Votre rôle ne dispose pas des autorisations nécessaires pour accéder à cette page.',
    required: 'Rôles autorisés',
    back: 'Retour au tableau de bord',
  },
  en: {
    code: 'ERROR 403',
    title: 'Access denied',
    body: 'Your role does not have the permissions required to view this page.',
    required: 'Allowed roles',
    back: 'Back to dashboard',
  },
  ar: {
    code: 'خطأ 403',
    title: 'تم رفض الوصول',
    body: 'لا يملك دورك الأذونات اللازمة لعرض هذه الصفحة.',
    required: 'الأدوار المسموح بها',
    back: 'العودة إلى لوحة التحكم',
  },
};

export function Forbidden({ requiredRoles }: { requiredRoles?: AdminRole[] }) {
  const locale = useLocaleStore((s) => s.locale);
  const c = COPY[locale as keyof typeof COPY] ?? COPY.en;

  return (
    <div className="flex h-full min-h-[60vh] flex-col items-center justify-center gap-5 px-6 text-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <IconLock size={30} className="text-[var(--text-muted)]" />
      </div>
      <div className="flex flex-col gap-1">
        <span className="text-xs font-bold uppercase tracking-[0.18em] text-[var(--text-muted)]">{c.code}</span>
        <h1 className="text-2xl font-semibold tracking-tight text-[var(--text-primary)]">{c.title}</h1>
        <p className="mt-1 max-w-md text-sm text-[var(--text-muted)]">{c.body}</p>
      </div>
      {requiredRoles && requiredRoles.length > 0 && (
        <div className="flex items-center gap-2 text-xs text-[var(--text-muted)]">
          <span>{c.required}:</span>
          {requiredRoles.map((r) => (
            <span key={r} className="rounded-full border border-[var(--border)] bg-[var(--surface)] px-2 py-0.5 font-mono font-semibold text-[var(--text-primary)]">
              {r}
            </span>
          ))}
        </div>
      )}
      <Link
        to="/dashboard"
        className="mt-2 inline-flex items-center gap-2 rounded-md bg-[var(--brand)] px-4 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90"
      >
        <IconArrowLeft size={16} />
        {c.back}
      </Link>
    </div>
  );
}

export default Forbidden;
