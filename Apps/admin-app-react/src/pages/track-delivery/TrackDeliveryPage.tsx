
import type { TranslationSchema } from '@/lib/i18n/LocaleContext';
import { useEffect, useRef, useState } from 'react';
import { lazy as dynamic } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Skeleton } from '@/components/ui/skeleton';
import { IconPhone, IconChevronUp, IconChevronDown, IconCheck, IconClipboardList, IconPackage, IconTruckDelivery, IconHomeCheck } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import ReturnSection from './ReturnSection';
import s from './TrackDelivery.module.scss';

const TrackingMap = dynamic(() => import('./TrackingMap'));

interface OrderItem { name: string; quantity: number; unitPrice?: number }
interface TrackingData {
  deliveryId: string; status: string; kind?: string; failReason?: string; returnStatus?: string; returnResolutionNote?: string; clientName?: string; clientPhone?: string; erpOrderId?: string
  dropoffLat?: number; dropoffLng?: number; dropoffAddress?: string; dropoffCity?: string
  driverName?: string; driverPhone?: string; driverLat?: number; driverLng?: number
  depotLat?: number; depotLng?: number; depotName?: string
  startWindow?: string; endWindow?: string; etaAt?: string
  routeGeometry?: string; companyName?: string; companyLogoUrl?: string
  totalAmount?: number; items?: OrderItem[]
}

const STEPS = ['SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED'];
const STEP_ICONS = [IconClipboardList, IconPackage, IconTruckDelivery, IconHomeCheck];

function getStatusConfig(t: TranslationSchema): Record<string, { label: string; sub: string; color: string }> {
  return {
    UNSCHEDULED: { label: t.trackingPage.statusEnAttente, sub: t.trackingPage.statusSubEnAttente, color: '#64748b' },
    SCHEDULED:   { label: t.trackingPage.statusPlanifiee, sub: t.trackingPage.statusSubPlanifiee, color: '#3b82f6' },
    PICKED_UP:   { label: t.trackingPage.statusPriseEnCharge, sub: t.trackingPage.statusSubPriseEnCharge, color: 'var(--brand)' },
    IN_TRANSIT:  { label: t.trackingPage.statusEnRoute, sub: t.trackingPage.statusSubEnRoute, color: 'var(--brand)' },
    DELIVERED:   { label: t.trackingPage.statusLivree, sub: t.trackingPage.statusSubLivree, color: '#16a34a' },
    PARTIALLY_DELIVERED: { label: t.trackingPage.statusPartielle, sub: t.trackingPage.statusSubPartielle, color: '#f59e0b' },
    FAILED:      { label: t.trackingPage.statusTentativeEchouee, sub: t.trackingPage.statusSubTentativeEchouee, color: '#ef4444' },
    CANCELLED:   { label: t.trackingPage.statusAnnulee, sub: t.trackingPage.statusSubAnnulee, color: '#ef4444' },
  };
}

function getStepLabels(t: TranslationSchema): string[] {
  return [t.trackingPage.stepPlanifiee, t.trackingPage.stepRecuperee, t.trackingPage.stepEnRoute, t.trackingPage.stepLivree];
}

function fmtTime(iso?: string | null) {
  if (!iso) return null;
  try { const d = new Date(iso); return isNaN(d.getTime()) ? null : d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }); }
  catch { return null; }
}

function fmtAmount(n: number) {
  return n.toLocaleString('fr-TN', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
}

import { useParams } from 'react-router-dom';

export default function TrackingPage() {
  const { deliveryId } = useParams<{ deliveryId: string }>();
  const [data, setData]         = useState<TrackingData | null>(null);
  const [error, setError]       = useState(false);
  const [loading, setLoading]   = useState(true);
  const [expanded, setExpanded] = useState(false);
  const stompRef                = useRef<Client | null>(null);
  const t = useT();

  const fetchData = async (signal?: AbortSignal) => {
    try {
      const res = await fetch(`/api/public/track/${deliveryId}`, { signal });
      if (!res.ok) { setError(true); return; }
      setData(await res.json());
      setError(false); // clear any prior/transient error once a load succeeds
    } catch (e) {
      // An aborted fetch (StrictMode double-mount / unmount) is expected, not a "not found".
      if ((e as { name?: string })?.name !== 'AbortError') setError(true);
    }
    finally { setLoading(false); }
  };

  useEffect(() => {
    const controller = new AbortController();
    fetchData(controller.signal);
    const t = setInterval(() => fetchData(), 60_000);
    const baseUrl = import.meta.env.VITE_WS_BASE_URL ?? import.meta.env.VITE_API_BASE_URL
      ?? (typeof window !== 'undefined' ? `${window.location.protocol}//${window.location.host}` : '');
    const client = new Client({
      webSocketFactory: () => new SockJS(`${baseUrl}/ws`),
      reconnectDelay: 5000, heartbeatIncoming: 10000, heartbeatOutgoing: 10000,
      onConnect: () => {
        client.subscribe(`/topic/public.${deliveryId}`, msg => {
          try {
            const { lat, lng } = JSON.parse(msg.body);
            setData(prev => prev ? { ...prev, driverLat: lat, driverLng: lng } : prev);
          } catch { /* ignore */ }
        });
      },
    });
    client.activate();
    stompRef.current = client;
    return () => { controller.abort(); clearInterval(t); client.deactivate(); };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (loading) return (
    <div style={{ position: 'fixed', inset: 0, background: '#e2e8f0', display: 'flex', justifyContent: 'center' }}>
      <div style={{ width: '100%', maxWidth: 520, height: '100%', background: '#fff', overflow: 'hidden' }}>
        <Skeleton className="h-[45%] w-full rounded-none" />
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <Skeleton className="h-[72px] w-full rounded-xl" />
          <Skeleton className="h-12 w-full rounded-xl" />
          <Skeleton className="h-[120px] w-full rounded-xl" />
          <Skeleton className="h-16 w-full rounded-xl" />
        </div>
      </div>
    </div>
  );

  if (error || !data) return (
    <div style={{ position: 'fixed', inset: 0, background: '#e2e8f0', display: 'flex', justifyContent: 'center' }}>
      <div style={{ width: '100%', maxWidth: 520, height: '100%', background: '#fff', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12, padding: 32 }}>
        <p style={{ fontSize: 17, fontWeight: 700, color: '#0f172a', margin: 0 }}>{t.trackingPage.notFoundTitle}</p>
        <p style={{ fontSize: 13, color: '#94a3b8', margin: 0, textAlign: 'center', maxWidth: 260 }}>
          {t.trackingPage.notFoundSub}
        </p>
      </div>
    </div>
  );

  const st      = getStatusConfig(t)[data.status] ?? getStatusConfig(t).UNSCHEDULED;
  const stepIdx = STEPS.indexOf(data.status);
  const eta     = fmtTime(data.etaAt);
  const hasItems = data.items && data.items.length > 0;
  const SHEET   = expanded ? '82dvh' : '54dvh';

  return (
    // Full-viewport neutral backdrop; the phone-style UI is centered and capped
    // at a max width so a customer opening the link on desktop doesn't get a
    // stretched mobile layout.
    <div className={s.page}>
    <div className={s.frame}>

      {/* Map — mobile: fills to the sheet edge; desktop: fluid left half (see module) */}
      <div className={s.mapWrap} style={{
        bottom: SHEET,
        transition: 'bottom 0.38s cubic-bezier(0.32,0.72,0,1)',
      }}>
        <TrackingMap data={data} />
      </div>

      {/* Top bar */}
      <div style={{
        position: 'absolute', top: 0, left: 0, right: 0, zIndex: 200,
        height: 56, background: '#fff',
        borderBottom: `3px solid ${st.color}`,
        display: 'flex', alignItems: 'center',
        padding: '0 16px', gap: 10,
        boxShadow: '0 1px 8px rgba(0,0,0,0.08)',
      }}>
        {data.companyLogoUrl
          ? <img src={data.companyLogoUrl} alt="" style={{ height: 30, width: 30, borderRadius: 6, objectFit: 'cover', border: '1px solid #f1f5f9' }} />
          : <div style={{ height: 30, width: 30, borderRadius: 6, background: '#f1f5f9', flexShrink: 0 }} />
        }
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: '#0f172a', lineHeight: 1 }}>{data.companyName ?? t.trackingPage.appTitle}</div>
          <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 2 }}>{t.trackingPage.pageTitle}</div>
        </div>
        {data.erpOrderId && (
          <div style={{ fontSize: 11, fontWeight: 700, color: '#94a3b8', fontFamily: 'monospace', background: '#f8fafc', padding: '4px 8px', borderRadius: 6, border: '1px solid #e2e8f0' }}>
            {data.erpOrderId}
          </div>
        )}
      </div>

      {/* Detail — mobile: bottom-sheet; desktop: fixed right panel (see module) */}
      <div className={s.panel} style={{
        height: SHEET,
        transition: 'height 0.38s cubic-bezier(0.32,0.72,0,1)',
        background: '#fff',
        borderRadius: '20px 20px 0 0',
        boxShadow: '0 -4px 24px rgba(0,0,0,0.10)',
        display: 'flex', flexDirection: 'column',
        overflow: 'hidden',
      }}>

        {/* Sheet header — status + stepper */}
        <div style={{ flexShrink: 0, borderBottom: '1px solid #f1f5f9' }}>

          {/* Handle + toggle */}
          <button
            className={s.handle}
            onClick={() => setExpanded(e => !e)}
            style={{ width: '100%', background: 'none', border: 'none', cursor: 'pointer', padding: '10px 0 8px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}
          >
            <div style={{ width: 32, height: 3, borderRadius: 2, background: '#e2e8f0' }} />
            {expanded ? <IconChevronDown size={14} color="#cbd5e1" /> : <IconChevronUp size={14} color="#cbd5e1" />}
          </button>

          {/* Status */}
          <div style={{ padding: '18px 20px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
              <div>
                {data.kind === 'RETURN_PICKUP' && (
                  <div style={{ display: 'inline-block', fontSize: 10, fontWeight: 800, color: '#7c3aed', background: '#f3e8ff', padding: '3px 8px', borderRadius: 999, letterSpacing: '0.04em', marginBottom: 6 }}>
                    COLLECTE RETOUR
                  </div>
                )}
                <div style={{ fontSize: 22, fontWeight: 800, color: '#0f172a', lineHeight: 1.1 }}>{st.label}</div>
                <div style={{ fontSize: 13, color: '#64748b', marginTop: 4 }}>{st.sub}</div>
              </div>
              {eta && (
                <div style={{ textAlign: 'right', flexShrink: 0 }}>
                  <div style={{ fontSize: 10, fontWeight: 600, color: '#94a3b8' }}>ETA</div>
                  <div style={{ fontSize: 20, fontWeight: 800, color: st.color, fontFamily: 'monospace', lineHeight: 1.1 }}>{eta}</div>
                </div>
              )}
            </div>

            {/* Failure reason — shown to the customer on failed / partial deliveries */}
            {data.failReason && (
              <div style={{ marginTop: 12, padding: '10px 12px', borderRadius: 10, background: '#fef2f2', border: '1px solid #fecaca' }}>
                <div style={{ fontSize: 10, fontWeight: 700, color: '#b91c1c', textTransform: 'uppercase', letterSpacing: '0.04em', marginBottom: 2 }}>{t.trackingPage.labelFailureReason}</div>
                <div style={{ fontSize: 13, color: '#7f1d1d', fontWeight: 600 }}>{data.failReason}</div>
              </div>
            )}

            {/* Return (RMA) status banner */}
            {data.returnStatus && (
              <div style={{ marginTop: 12, padding: '10px 12px', borderRadius: 10, background: '#fffbeb', border: '1px solid #fde68a' }}>
                <div style={{ fontSize: 10, fontWeight: 700, color: '#b45309', textTransform: 'uppercase', letterSpacing: '0.04em', marginBottom: 2 }}>{t.trackingPage.labelReturn}</div>
                <div style={{ fontSize: 13, color: '#92400e', fontWeight: 600 }}>
                  {(t.trackingPage.returnStatusLabels as Record<string, string>)[data.returnStatus] ?? data.returnStatus}
                </div>
              </div>
            )}

            {/* Stepper — modern icon timeline: filled progress, check for done, pulsing current */}
            {stepIdx >= 0 && (
              <div style={{ display: 'flex', alignItems: 'flex-start', marginTop: 22 }}>
                {STEPS.map((step, i) => {
                  const reached = stepIdx >= i;   // node coloured
                  const done    = stepIdx > i;    // past → check
                  const active  = stepIdx === i;  // current → icon + pulse
                  const StepIcon = STEP_ICONS[i];
                  return (
                    <div key={step} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                      <div style={{ display: 'flex', alignItems: 'center', width: '100%' }}>
                        <div style={{ flex: 1, height: 3, borderRadius: 2, background: (i > 0 && stepIdx >= i) ? st.color : '#e5e7eb', transition: 'background 0.3s' }} />
                        <div style={{
                          position: 'relative', width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
                          display: 'flex', alignItems: 'center', justifyContent: 'center',
                          background: reached ? st.color : '#fff',
                          border: reached ? 'none' : '2px solid #e5e7eb',
                          boxShadow: active ? `0 0 0 4px ${st.color}22` : 'none',
                          transition: 'all 0.3s',
                        }}>
                          {active && <span style={{ position: 'absolute', inset: -3, borderRadius: '50%', background: st.color, opacity: 0.18, animation: 'trkPulse 1.6s ease-out infinite' }} />}
                          {done
                            ? <IconCheck size={16} color="#fff" stroke={3} />
                            : <StepIcon size={15} color={reached ? '#fff' : '#94a3b8'} stroke={2} />}
                        </div>
                        <div style={{ flex: 1, height: 3, borderRadius: 2, background: (i < STEPS.length - 1 && stepIdx > i) ? st.color : '#e5e7eb', transition: 'background 0.3s' }} />
                      </div>
                      <div style={{ fontSize: 11, fontWeight: active ? 800 : 600, color: active ? st.color : reached ? '#334155' : '#cbd5e1', marginTop: 8, textAlign: 'center', lineHeight: 1.2 }}>
                        {getStepLabels(t)[i]}
                      </div>
                    </div>
                  );
                })}
                <style>{`@keyframes trkPulse{0%{transform:scale(1);opacity:0.22}100%{transform:scale(1.7);opacity:0}}@media (prefers-reduced-motion:reduce){[style*="trkPulse"]{animation:none!important}}`}</style>
              </div>
            )}
          </div>
        </div>

        {/* Scrollable body */}
        <div style={{ flex: 1, overflowY: 'auto' }}>

          {/* Driver */}
          {(data.driverName || data.driverPhone) && (
            <div style={{ padding: '16px 20px', borderBottom: '1px solid #f1f5f9', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div style={{
                  width: 42, height: 42, borderRadius: '50%', background: '#f8fafc',
                  border: '1px solid #e2e8f0', display: 'flex', alignItems: 'center',
                  justifyContent: 'center', fontSize: 16, fontWeight: 800, color: '#334155', flexShrink: 0,
                }}>
                  {data.driverName?.[0] ?? '?'}
                </div>
                <div>
                  <div style={{ fontSize: 11, color: '#94a3b8', fontWeight: 600 }}>{t.trackingPage.labelDriver}</div>
                  <div style={{ fontSize: 15, fontWeight: 700, color: '#0f172a' }}>{data.driverName}</div>
                </div>
              </div>
              {data.driverPhone && (
                <a href={`tel:${data.driverPhone}`} style={{
                  display: 'flex', alignItems: 'center', gap: 7,
                  background: st.color, color: '#fff',
                  padding: '9px 16px', borderRadius: 10,
                  fontSize: 13, fontWeight: 700, textDecoration: 'none', flexShrink: 0,
                }}>
                  <IconPhone size={14} stroke={2.5} />
                  {t.trackingPage.labelCall}
                </a>
              )}
            </div>
          )}

          {/* Order */}
          <div style={{ padding: '16px 20px', borderBottom: '1px solid #f1f5f9' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#94a3b8' }}>
                {t.trackingPage.sectionContents}
              </div>
            </div>

            {hasItems ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {data.items!.map((item, i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
                      <div style={{ width: 28, height: 28, borderRadius: 6, background: '#f8fafc', border: '1px solid #e2e8f0', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                        <span style={{ fontSize: 10, fontWeight: 800, color: '#64748b' }}>×{item.quantity}</span>
                      </div>
                      <span style={{ fontSize: 13, color: '#334155', fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {item.name}
                      </span>
                    </div>
                    {item.unitPrice != null && (
                      <span style={{ fontSize: 13, fontWeight: 700, color: '#0f172a', fontFamily: 'monospace', whiteSpace: 'nowrap', flexShrink: 0 }}>
                        {fmtAmount(item.unitPrice * item.quantity)}
                        <span style={{ fontSize: 10, color: '#94a3b8', fontWeight: 400 }}> {t.trackingPage.currencyTnd}</span>
                      </span>
                    )}
                  </div>
                ))}

                {data.totalAmount != null && (
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingTop: 10, borderTop: '1px solid #f1f5f9', marginTop: 4 }}>
                    <span style={{ fontSize: 12, fontWeight: 600, color: '#94a3b8' }}>{t.trackingPage.labelTotal}</span>
                    <span style={{ fontSize: 17, fontWeight: 800, color: '#0f172a', fontFamily: 'monospace' }}>
                      {fmtAmount(data.totalAmount)}
                      <span style={{ fontSize: 11, fontWeight: 500, color: '#94a3b8' }}> {t.trackingPage.currencyTnd}</span>
                    </span>
                  </div>
                )}
              </div>
            ) : (
              <p style={{ fontSize: 13, color: '#94a3b8', margin: 0 }}>{t.trackingPage.noItems}</p>
            )}

            {/* Self-service return (client) */}
            <ReturnSection
              deliveryId={deliveryId!}
              returnStatus={data.returnStatus}
              returnResolutionNote={data.returnResolutionNote}
              onChanged={() => fetchData()}
            />
          </div>

          {/* Address */}
          <div style={{ padding: '16px 20px', borderBottom: '1px solid #f1f5f9' }}>
            <div style={{ fontSize: 11, fontWeight: 700, color: '#94a3b8', marginBottom: 8 }}>
              {t.trackingPage.sectionAddress}
            </div>
            <div style={{ fontSize: 14, fontWeight: 700, color: '#0f172a' }}>{data.clientName}</div>
            {data.dropoffAddress && <div style={{ fontSize: 12, color: '#64748b', marginTop: 3, lineHeight: 1.5 }}>{data.dropoffAddress}</div>}
            {data.dropoffCity && <div style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginTop: 2 }}>{data.dropoffCity}</div>}
          </div>

          {/* Window */}
          {(data.startWindow || data.endWindow) && (
            <div style={{ padding: '16px 20px', borderBottom: '1px solid #f1f5f9', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#94a3b8' }}>
                {t.trackingPage.sectionSlot}
              </div>
              <div style={{ fontSize: 15, fontWeight: 800, color: '#0f172a', fontFamily: 'monospace' }}>
                {data.startWindow ?? '—'} – {data.endWindow ?? '—'}
              </div>
            </div>
          )}

          <div style={{ padding: '16px 20px', textAlign: 'center' }}>
            <span style={{ fontSize: 11, color: '#cbd5e1' }}>{t.trackingPage.liveTracking}{data.companyName ?? t.trackingPage.appTitle}</span>
          </div>
        </div>
      </div>
    </div>
    </div>
  );
}
