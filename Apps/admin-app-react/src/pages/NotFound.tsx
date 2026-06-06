import { Link } from 'react-router-dom';
import { IconCompass, IconArrowLeft } from '@tabler/icons-react';
import { useLocaleStore } from '@/lib/i18n';

const COPY = {
  fr: { code: 'ERREUR 404', title: 'Page introuvable', body: "Cette page n'existe pas ou a été déplacée.", back: 'Retour au tableau de bord' },
  en: { code: 'ERROR 404', title: 'Page not found', body: 'This page does not exist or has been moved.', back: 'Back to dashboard' },
  ar: { code: 'خطأ 404', title: 'الصفحة غير موجودة', body: 'هذه الصفحة غير موجودة أو تم نقلها.', back: 'العودة إلى لوحة التحكم' },
};

export default function NotFound() {
  const locale = useLocaleStore((s) => s.locale);
  const c = COPY[locale as keyof typeof COPY] ?? COPY.en;

  return (
    <div className="flex h-full min-h-[60vh] flex-col items-center justify-center gap-5 px-6 text-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <IconCompass size={30} className="text-[var(--text-muted)]" />
      </div>
      <div className="flex flex-col gap-1">
        <span className="text-[11px] font-bold uppercase tracking-[0.18em] text-[var(--text-muted)]">{c.code}</span>
        <h1 className="text-2xl font-semibold tracking-tight text-[var(--text-primary)]">{c.title}</h1>
        <p className="mt-1 max-w-md text-sm text-[var(--text-muted)]">{c.body}</p>
      </div>
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
