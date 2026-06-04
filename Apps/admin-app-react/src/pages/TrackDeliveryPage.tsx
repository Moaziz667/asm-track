
import { useEffect, useRef, useState } from 'react';
import { lazy as dynamic } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Skeleton } from '@mantine/core';
import { IconPhone, IconChevronUp, IconChevronDown } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';

const TrackingMap = dynamic(() => import('./TrackingMap'));

interface OrderItem { name: string; quantity: number; unitPrice?: number }
interface TrackingData {
  deliveryId: string; status: string; clientName?: string; clientPhone?: string; erpOrderId?: string
  dropoffLat?: number; dropoffLng?: number; dropoffAddress?: string; dropoffCity?: string
  driverName?: string; driverPhone?: string; driverLat?: number; driverLng?: number
  depotLat?: number; depotLng?: number; depotName?: string
  startWindow?: string; endWindow?: string; etaAt?: string
  routeGeometry?: string; companyName?: string; companyLogoUrl?: string
  totalAmount?: number; items?: OrderItem[]
}

const STEPS = ['SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED'];

function getStatusConfig(t: any): Record<string, { label: string; sub: string; color: string }> {
  return {
    UNSCHEDULED: { label: t.trackingPage.statusEnAttente, sub: t.trackingPage.statusSubEnAttente, color: '#64748b' },
    SCHEDULED:   { label: t.trackingPage.statusPlanifiee, sub: t.trackingPage.statusSubPlanifiee, color: '#3b82f6' },
    PICKED_UP:   { label: t.trackingPage.statusPriseEnCharge, sub: t.trackingPage.statusSubPriseEnCharge, color: 'var(--brand)' },
    IN_TRANSIT:  { label: t.trackingPage.statusEnRoute, sub: t.trackingPage.statusSubEnRoute, color: 'var(--brand)' },
    DELIVERED:   { label: t.trackingPage.statusLivree, sub: t.trackingPage.statusSubLivree, color: '#16a34a' },
    FAILED:      { label: t.trackingPage.statusTentativeEchouee, sub: t.trackingPage.statusSubTentativeEchouee, color: '#ef4444' },
    CANCELLED:   { label: t.trackingPage.statusAnnulee, sub: t.trackingPage.statusSubAnnulee, color: '#ef4444' },
  };
}

function getStepLabels(t: any): string[] {
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

  const fetchData = async () => {
    try {
      const res = await fetch(`/api/public/track/${deliveryId}`);
      if (!res.ok) { setError(true); return; }
      setData(await res.json());
    } catch { setError(true); }
    finally { setLoading(false); }
  };

  useEffect(() => {
    fetchData();
    const t = setInterval(fetchData, 60_000);
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
    return () => { clearInterval(t); client.deactivate(); };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (loading) return (
    <div style={{ position: 'fixed', inset: 0, background: '#fff' }}>
      <Skeleton height="45%" radius={0} />
      <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
        <Skeleton height={72} radius={12} />
        <Skeleton height={48} radius={12} />
        <Skeleton height={120} radius={12} />
        <Skeleton height={64} radius={12} />
      </div>
    </div>
  );

  if (error || !data) return (
    <div style={{ position: 'fixed', inset: 0, background: '#fff', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12, padding: 32 }}>
      <p style={{ fontSize: 17, fontWeight: 700, color: '#0f172a', margin: 0 }}>{t.trackingPage.notFoundTitle}</p>
      <p style={{ fontSize: 13, color: '#94a3b8', margin: 0, textAlign: 'center', maxWidth: 260 }}>
        {t.trackingPage.notFoundSub}
      </p>
    </div>
  );

  const st      = getStatusConfig(t)[data.status] ?? getStatusConfig(t).UNSCHEDULED;
  const stepIdx = STEPS.indexOf(data.status);
  const eta     = fmtTime(data.etaAt);
  const hasItems = data.items && data.items.length > 0;
  const SHEET   = expanded ? '82dvh' : '54dvh';

  return (
    <div style={{ position: 'fixed', inset: 0, fontFamily: '"IBM Plex Sans", -apple-system, sans-serif' }}>

      {/* Map — fills from top-0 to bottom-sheet edge, sits behind the top bar */}
      <div style={{
        position: 'absolute', top: 0, left: 0, right: 0,
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

      {/* Bottom sheet */}
      <div style={{
        position: 'absolute', bottom: 0, left: 0, right: 0, zIndex: 100,
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
            onClick={() => setExpanded(e => !e)}
            style={{ width: '100%', background: 'none', border: 'none', cursor: 'pointer', padding: '10px 0 8px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}
          >
            <div style={{ width: 32, height: 3, borderRadius: 2, background: '#e2e8f0' }} />
            {expanded ? <IconChevronDown size={14} color="#cbd5e1" /> : <IconChevronUp size={14} color="#cbd5e1" />}
          </button>

          {/* Status */}
          <div style={{ padding: '0 20px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
              <div>
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

            {/* Stepper */}
            {stepIdx >= 0 && (
              <div style={{ display: 'flex', alignItems: 'center', marginTop: 16 }}>
                {STEPS.map((step, i) => {
                  const done   = stepIdx >= i;
                  const active = stepIdx === i;
                  return (
                    <div key={step} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                      <div style={{ display: 'flex', alignItems: 'center', width: '100%' }}>
                        {i > 0 && <div style={{ flex: 1, height: 2, background: done ? st.color : '#e2e8f0', transition: 'background 0.3s' }} />}
                        <div style={{
                          width: 10, height: 10, borderRadius: '50%', flexShrink: 0,
                          background: done ? st.color : '#e2e8f0',
                          outline: active ? `3px solid ${st.color}33` : 'none',
                          outlineOffset: 2,
                          transition: 'all 0.3s',
                        }} />
                        {i < STEPS.length - 1 && <div style={{ flex: 1, height: 2, background: stepIdx > i ? st.color : '#e2e8f0', transition: 'background 0.3s' }} />}
                      </div>
                      <div style={{ fontSize: 9, fontWeight: 600, color: done ? '#475569' : '#cbd5e1', marginTop: 5, textAlign: 'center', letterSpacing: '0.02em' }}>
                        {getStepLabels(t)[i]}
                      </div>
                    </div>
                  );
                })}
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
  );
}
