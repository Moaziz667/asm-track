import { AppModal } from '@/components/overlays/AppModal';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { cn } from '@/lib/utils';
import type { Driver, Delivery } from '@/types';
import type { useT } from '@/lib/i18n/LocaleContext';
import { DriverStatusBadge } from './DriverStatusBadge';
import { DriverAvatar } from '@/components/data-display/DriverAvatar';
import { SVGPencil, SVGActivity } from './icons';
import { TERMINAL_STATUSES } from './constants';

interface Props {
  open: boolean;
  onClose: () => void;
  driver: Driver | null;
  deliveries: Delivery[];
  detailLoading: boolean;
  activeTab: 'info' | 'mission' | 'activity';
  setActiveTab: (t: 'info' | 'mission' | 'activity') => void;
  readOnly: boolean;
  locale: string;
  t: ReturnType<typeof useT>;
  resendCooldown: number;
  isDriverEnLivraison: (d: Driver) => boolean;
  onEdit: (drv: Driver) => void;
  onResendInvite: (drv: Driver) => void;
  onCancelInvite: (drv: Driver) => void;
}

export function DriverDetailsModal({
  open, onClose, driver: selected, deliveries, detailLoading, activeTab, setActiveTab,
  readOnly, locale, t, resendCooldown, isDriverEnLivraison, onEdit, onResendInvite, onCancelInvite,
}: Props) {
  const historyDeliveriesForSelected = deliveries.filter((d) => TERMINAL_STATUSES.has(d.status));

  const tabInfoDesc = locale === 'ar'
    ? 'المعلومات الشخصية، بيانات الاتصال، وحالة تنشيط حساب السائق.'
    : locale === 'en'
      ? 'Personal information, contact details, and driver account activation status.'
      : 'Informations personnelles, coordonnées et statut d\'activation du compte chauffeur.';
  const tabMissionDesc = locale === 'ar'
    ? 'مؤشرات الرحلة النشطة في الوقت الفعلي، تقدم المسار وتتبع إحداثيات الموقع.'
    : locale === 'en'
      ? 'Real-time active mission indicators, route progression, and GPS coordinate tracking.'
      : 'Indicateurs de mission active en temps réel, progression de la tournée et coordonnées GPS.';
  const tabActivityDesc = locale === 'ar'
    ? 'سجل محاولات التسليم المكتملة أو الملغاة أو الفاشلة التي قام بها هذا السائق.'
    : locale === 'en'
      ? 'History of completed, cancelled, or failed delivery attempts managed by this driver.'
      : 'Historique des tentatives de livraison terminées, annulées ou échouées gérées par ce chauffeur.';

  return (
    <AppModal
      open={open}
      onClose={onClose}
      title={selected ? selected.name : ''}
      subtitle={selected ? `${t.driversPage.profileModalTitle} · ID ${selected.id.slice(0, 8).toUpperCase()}` : ''}
      size="md"
      footer={
        <div className="flex items-center justify-end gap-3 w-full mt-2">
          {!readOnly && selected && selected.accountStatus !== 'PENDING_SETUP' && (
            <button
              type="button"
              className="h-8 px-4 border border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] text-[var(--text-soft)] hover:text-[var(--text-primary)] font-semibold text-xs rounded-full transition-colors flex items-center justify-center gap-1.5"
              onClick={() => { onClose(); onEdit(selected); }}
            >
              <SVGPencil size={13} />
              {t.driversPage.modifyButton}
            </button>
          )}
          <Button size="sm" onClick={onClose} className="h-7 px-3 text-xs font-bold rounded-md bg-[var(--brand)] hover:opacity-90 text-white border-none">
            {t.driversPage.cancelButton || 'Fermer'}
          </Button>
        </div>
      }
    >
      {selected && (
        <div className="flex flex-col w-full">
          <div className="border-b border-[var(--border)]/40 shrink-0 pb-2.5 mb-5 flex gap-7 justify-start">
            {([
              ['info', t.driversPage.profileModalTitle],
              ['mission', t.driversPage.fleetStatusLabel],
              ['activity', t.driversPage.tabFilters],
            ] as const).map(([tab, label]) => {
              const active = activeTab === tab;
              return (
                <button
                  key={tab}
                  type="button"
                  onClick={() => setActiveTab(tab)}
                  className={cn('text-xs font-semibold pb-1.5 transition-all text-start relative bg-transparent border-0 cursor-pointer outline-none',
                    active ? 'text-[var(--brand)] font-bold' : 'text-[var(--text-soft)] hover:text-[var(--text-primary)]')}
                >
                  {label}
                  {active && <span className="absolute bottom-0 left-0 right-0 h-[2px] bg-[var(--brand)] rounded-full" style={{ bottom: '-10px' }} />}
                </button>
              );
            })}
          </div>

          <div dir={locale === 'ar' ? 'rtl' : 'ltr'} className="flex-1 overflow-y-auto max-h-[380px] pr-1 pl-1 text-start" style={{ scrollbarWidth: 'thin' }}>
            {/* Tab 1: Profile Info */}
            {activeTab === 'info' && (
              <div className="space-y-6">
                <div className="flex items-center gap-4 pb-4 border-b border-[var(--border)]/60">
                  <DriverAvatar name={selected.name} photoUrl={selected.photoUrl} size={48} />
                  <div className="flex-1 min-w-0">
                    <span className="text-sm font-bold text-[var(--text-primary)] block truncate">{selected.name}</span>
                    <span className="text-xs text-[var(--text-muted)] font-mono block truncate">{selected.email || '—'}</span>
                  </div>
                  <DriverStatusBadge status={selected.accountStatus ?? 'PENDING_SETUP'} size="sm" />
                </div>

                <p className="text-xs text-[var(--text-soft)] leading-relaxed italic pr-2 pl-2">{tabInfoDesc}</p>

                <div className="flex flex-col gap-1 pr-2 pl-2">
                  <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] mb-3 block">
                    {t.driversPage.tableHeaderContact || 'Coordonnées'}
                  </span>
                  <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                    <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.nameLabel}</span>
                    <span className="text-xs text-[var(--text-primary)] dark:text-white font-bold">{selected.name}</span>
                  </div>
                  <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                    <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.phoneLabel}</span>
                    <span className="text-xs text-[var(--text-primary)] dark:text-white font-mono font-bold">{selected.phone}</span>
                  </div>
                  {selected.email && (
                    <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                      <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.emailLabel}</span>
                      <span className="text-xs text-[var(--text-primary)] font-mono font-bold truncate max-w-[200px]">{selected.email}</span>
                    </div>
                  )}
                  <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                    <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.tableHeaderActivity}</span>
                    <span className="inline-flex items-center gap-1.5 text-xs text-[var(--text-primary)] font-semibold">
                      <span className={cn('block h-1.5 w-1.5 rounded-full', selected.onlineStatus === 'ONLINE' ? 'bg-emerald-500' : 'bg-[var(--text-soft)]')} />
                      {selected.onlineStatus === 'ONLINE' ? (t.driversPage.statusOnline ?? 'En ligne') : (t.driversPage.statusOffline ?? 'Hors ligne')}
                    </span>
                  </div>
                  {selected.suspendedReason && (
                    <div className="flex flex-col gap-2 pt-3">
                      <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.suspendReasonLabel}</span>
                      <span className="text-xs font-medium text-[var(--danger)] bg-[rgba(199,55,47,0.06)] border border-[rgba(199,55,47,0.15)] px-3 py-2.5 rounded">
                        {selected.suspendedReason}
                      </span>
                    </div>
                  )}
                </div>

                {!readOnly && selected.accountStatus === 'PENDING_SETUP' && (
                  <div className="pt-3 flex flex-col gap-2.5 pr-2 pl-2">
                    <button
                      type="button"
                      onClick={() => onResendInvite(selected)}
                      disabled={resendCooldown > 0}
                      className="w-full h-9 bg-[var(--brand)] hover:opacity-90 disabled:opacity-50 text-white font-bold text-xs rounded transition-opacity"
                    >
                      {resendCooldown > 0 ? t.driversPage.resendCooldown.replace('{seconds}', String(resendCooldown)) : t.driversPage.resendInviteButton}
                    </button>
                    <button
                      type="button"
                      onClick={() => onCancelInvite(selected)}
                      className="w-full h-9 border border-red-200 text-red-600 hover:bg-red-50/20 font-bold text-xs rounded transition-colors"
                    >
                      {t.driversPage.cancelInviteButton}
                    </button>
                  </div>
                )}
              </div>
            )}

            {/* Tab 2: Active Mission */}
            {activeTab === 'mission' && (
              <div className="space-y-5">
                <p className="text-xs text-[var(--text-soft)] leading-relaxed italic pr-2 pl-2">{tabMissionDesc}</p>
                {isDriverEnLivraison(selected) ? (
                  <div className="space-y-5 pr-2 pl-2">
                    <div className="flex flex-col gap-1">
                      <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] mb-3 block">
                        {t.driversPage.fleetStatusLabel || 'Mission en cours'}
                      </span>
                      {selected.activeRouteId && (
                        <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                          <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.onRoute}</span>
                          <div className="flex items-center gap-2">
                            <StatusBadge status="IN_PROGRESS" size="sm" label={t.driversPage.onRoute} />
                            <span className="text-xs text-[var(--text-primary)] font-mono font-bold">{selected.activeRouteId.slice(0, 8).toUpperCase()}</span>
                          </div>
                        </div>
                      )}
                      {selected.activeDeliveryId && (
                        <div className="flex items-center justify-between py-3 border-b border-[var(--border)]/40">
                          <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.activeDelivery}</span>
                          <div className="flex items-center gap-2">
                            <StatusBadge status="IN_TRANSIT" size="sm" label={t.driversPage.activeDelivery} />
                            <span className="text-xs text-[var(--text-primary)] font-mono font-bold">{selected.activeDeliveryId.slice(0, 8).toUpperCase()}</span>
                          </div>
                        </div>
                      )}
                      {(selected.currentLat || selected.currentLng) && (
                        <div className="flex flex-col gap-2 py-3">
                          <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.tableHeaderActivity || 'Position GPS'}</span>
                          <span className="text-xs font-mono text-[var(--text-soft)] bg-[var(--hover-bg)] p-3 border border-[var(--border)] rounded select-all text-center">
                            {selected.currentLat?.toFixed(6)}, {selected.currentLng?.toFixed(6)}
                          </span>
                        </div>
                      )}
                    </div>
                  </div>
                ) : (
                  <div className="py-14 text-center pr-2 pl-2">
                    <div className="p-3 bg-[rgba(76,175,130,0.06)] text-[var(--success)] rounded-full w-fit mx-auto mb-3 border border-[rgba(76,175,130,0.12)]">
                      <SVGActivity size={20} />
                    </div>
                    <h4 className="text-xs font-bold text-[var(--text-primary)]">{t.driversPage.free}</h4>
                    <p className="text-xs text-[var(--text-muted)] mt-1.5">{t.driversPage.noActivityDetected}</p>
                  </div>
                )}
              </div>
            )}

            {/* Tab 3: Recent Activity */}
            {activeTab === 'activity' && (
              <div className="space-y-4">
                <p className="text-xs text-[var(--text-soft)] leading-relaxed italic mb-3 pr-2 pl-2">{tabActivityDesc}</p>
                <div className="space-y-1.5 pr-2 pl-2">
                  {detailLoading ? (
                    Array.from({ length: 3 }).map((_, i) => (
                      <div key={i} className="h-12 w-full rounded bg-[var(--hover-bg)] animate-pulse" />
                    ))
                  ) : historyDeliveriesForSelected.length === 0 ? (
                    <div className="py-10 border border-dashed border-[var(--border)] rounded text-center bg-[var(--hover-bg)]/20">
                      <span className="text-xs text-[var(--text-muted)] font-medium">{t.driversPage.noActivityDetected}</span>
                    </div>
                  ) : (
                    historyDeliveriesForSelected.slice(0, 10).map((d) => (
                      <div key={d.id} className="py-3 border-b border-[var(--border)]/40 flex items-center justify-between gap-3 text-start">
                        <div className="flex items-center gap-3 overflow-hidden">
                          <StatusBadge status={d.status} size="sm" />
                          <span className="text-xs font-bold text-[var(--text-primary)] truncate">{d.clientName}</span>
                        </div>
                        <span className="text-xs font-bold text-[var(--text-muted)] font-mono shrink-0">
                          {d.orderRef || d.erpId || d.orderId?.slice(0, 8)}
                        </span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </AppModal>
  );
}
