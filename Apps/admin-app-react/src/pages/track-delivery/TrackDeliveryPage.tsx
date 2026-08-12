
import type { TranslationSchema } from '@/lib/i18n/LocaleContext';
import React, { useEffect, useRef, useState } from 'react';
import { lazy as dynamic } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Skeleton } from '@/components/ui/skeleton';
import { IconPhone, IconChevronUp, IconChevronDown, IconCheck, IconCopy, IconClipboardList, IconPackage, IconTruckDelivery, IconHomeCheck } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import ReturnSection from './ReturnSection';
import s from './TrackDelivery.module.scss';

const TrackingMap = dynamic(() => import('./TrackingMap'));

interface OrderItem { name: string; quantity: number; unitPrice?: number }
interface TrackingData {
  deliveryId: string; status: string; kind?: string; failReason?: string; returnStatus?: string; returnResolutionNote?: string; clientName?: string; clientPhone?: string; erpOrderId?: string; customerRef?: string
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

/**
 * Ink ramp for the public page.
 *
 * This page is deliberately token-free: it renders for a customer who has never seen the admin app,
 * outside its theme, so it carries its own light-only palette. That freedom had drifted into text
 * set in `#94a3b8` (2.8:1 on white) and a footer in `#cbd5e1` (1.6:1) — the latter is barely a
 * shade away from invisible. WCAG 1.4.3 asks 4.5:1 for body text, and this is the one screen the
 * business does not control the viewing conditions for: a courier link is read outdoors, at arm's
 * length, on a phone at half brightness.
 *
 * `muted` is the floor for anything a customer must read. `faint` is reserved for genuinely
 * inactive indicators (a step not yet reached) and pure ornament (the sheet handle).
 */
const INK = {
  strong: '#0f172a',
  body:   '#334155',
  muted:  '#64748b', // 4.8:1 on white — the lightest grey allowed to carry text
  faint:  '#94a3b8', // inactive/ornamental only, never a sentence
  hairline: '#f1f5f9',
  border: '#e2e8f0',
};

/** Visually hidden, still announced — for state a sighted user reads from colour alone. */
const SR_ONLY: React.CSSProperties = {
  position: 'absolute', width: 1, height: 1, padding: 0, margin: -1,
  overflow: 'hidden', clip: 'rect(0,0,0,0)', whiteSpace: 'nowrap', border: 0,
};

function fmtTime(iso?: string | null) {
  if (!iso) return null;
  try { const d = new Date(iso); return isNaN(d.getTime()) ? null : d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }); }
  catch { return null; }
}

/**
 * The day the parcel arrives, not just the hour.
 *
 * The ETA rendered as a bare "14:35". Baymard's order-tracking research puts the expected delivery
 * *date* first among the details customers look for — they open the link to plan around it — and a
 * clock time with no day silently reads as "today". For a delivery scheduled for tomorrow morning
 * that is not a cosmetic problem: it is the page telling the customer to wait in today.
 *
 * Today and tomorrow are named rather than dated, because that is how the answer gets used.
 */
function fmtEtaDay(iso: string | null | undefined, t: TranslationSchema): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (isNaN(d.getTime())) return null;
  const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((startOfDay(d) - startOfDay(new Date())) / 86_400_000);
  if (days === 0) return t.trackingPage.etaToday;
  if (days === 1) return t.trackingPage.etaTomorrow;
  return d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
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
  const [copied, setCopied]     = useState(false);
  const stompRef                = useRef<Client | null>(null);
  const t = useT();

  const fetchData = async (signal?: AbortSignal) => {
    try {
      const res = await fetch(`/api/v1/public/track/${deliveryId}`, { signal });
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
            // The server sends a CloudEvent — {specversion, id, source, type, time, data} — and the
            // coordinates live under `data`. Destructuring lat/lng off the envelope read undefined
            // every time, so the customer's map never moved: the van sat at its first reported
            // position for the whole delivery, and the page looked live while being frozen.
            // Both shapes are accepted so a raw payload from an older publisher still works.
            const body = JSON.parse(msg.body);
            const point = body?.data ?? body;
            const lat = point?.lat;
            const lng = point?.lng;
            if (typeof lat !== 'number' || typeof lng !== 'number') return;
            setData(prev => prev ? { ...prev, driverLat: lat, driverLng: lng } : prev);
          } catch { /* ignore */ }
        });
      },
    });
    client.activate();
    stompRef.current = client;
    return () => { controller.abort(); clearInterval(t); client.deactivate(); };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /*
    Name the tab after the parcel.

    A tracking link is something customers leave open and come back to, often beside three other
    tabs from the same shop. The document title never changed from the admin app's default, so all
    of them read alike and the status — the thing worth glancing at — was only visible after
    switching. The reference disambiguates; the status answers the question without a click.
  */
  useEffect(() => {
    if (!data) return;
    const label = getStatusConfig(t)[data.status]?.label ?? '';
    const ref = data.customerRef ?? data.erpOrderId;
    document.title = [label, ref, data.companyName ?? t.trackingPage.appTitle]
      .filter(Boolean).join(' · ');
  }, [data, t]);

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
        <p style={{ fontSize: 13, color: INK.muted, margin: 0, textAlign: 'center', maxWidth: 260 }}>
          {t.trackingPage.notFoundSub}
        </p>
      </div>
    </div>
  );

  const st      = getStatusConfig(t)[data.status] ?? getStatusConfig(t).UNSCHEDULED;
  const stepIdx = STEPS.indexOf(data.status);
  const eta     = fmtTime(data.etaAt);
  const etaDay  = fmtEtaDay(data.etaAt, t);
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
        {/*
            `contain` inside a free width, not `cover` inside a 30×30 square.

            A logo's shape is the information. Cropping it to a circle-ish tile is an avatar's
            treatment — fine for a face, destructive for a wordmark: this tenant's logo is 595×267,
            so a square frame with `cover` threw away 55 % of its width and left an unreadable
            middle slice on the one page customers actually see.
        */}
        {data.companyLogoUrl
          ? <img src={data.companyLogoUrl} alt={data.companyName ?? ''}
                 style={{ height: 30, maxWidth: 110, objectFit: 'contain', flexShrink: 0 }} />
          : <div style={{ height: 30, width: 30, borderRadius: 6, background: '#f1f5f9', flexShrink: 0 }} />
        }
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: '#0f172a', lineHeight: 1 }}>{data.companyName ?? t.trackingPage.appTitle}</div>
          <div style={{ fontSize: 11, color: INK.muted, marginTop: 2 }}>{t.trackingPage.pageTitle}</div>
        </div>
        {/* The recipient's own reference when we have it: erpOrderId is the distributor's
            delivery-note number and means nothing to the person waiting for the parcel. */}
        {(data.customerRef ?? data.erpOrderId) && (
          /*
            Copyable, not just displayed.

            Baymard's tracking research asks for one-click access to the reference: customers quote
            it into a chat window or a phone call to support. There is no carrier site to link to
            here — we are the carrier — so the useful affordance is the copy, and selecting eight
            monospace characters on a phone is precisely the friction it removes.
          */
          <button
            type="button"
            onClick={() => { void navigator.clipboard?.writeText(data.customerRef ?? data.erpOrderId!).then(() => setCopied(true)); }}
            title={t.trackingPage.copyRef}
            aria-label={`${t.trackingPage.copyRef} ${data.customerRef ?? data.erpOrderId}`}
            style={{
              display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer',
              fontSize: 11, fontWeight: 700, color: INK.muted, fontFamily: 'monospace',
              background: '#f8fafc', padding: '4px 8px', borderRadius: 6, border: `1px solid ${INK.border}`,
            }}
          >
            {data.customerRef ?? data.erpOrderId}
            {copied ? <IconCheck size={12} stroke={2.5} color="#16a34a" /> : <IconCopy size={12} stroke={2} />}
            <span style={SR_ONLY} role="status">{copied ? t.trackingPage.copied : ''}</span>
          </button>
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
            {expanded ? <IconChevronDown size={14} color={INK.faint} /> : <IconChevronUp size={14} color={INK.faint} />}
          </button>

          {/*
            Status block.

            `role="status"` + `aria-live="polite"`: the delivery state and the driver's position
            arrive over a websocket, so this text changes under a reader who is not touching the
            page. Without a live region a blind customer waiting for "En route" is told nothing
            when it happens — the one event this page exists to report.
          */}
          <div style={{ padding: '18px 20px 16px' }} role="status" aria-live="polite">
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
              <div>
                {data.kind === 'RETURN_PICKUP' && (
                  <div style={{ display: 'inline-block', fontSize: 10, fontWeight: 800, color: '#7c3aed', background: '#f3e8ff', padding: '3px 8px', borderRadius: 999, letterSpacing: '0.04em', marginBottom: 6 }}>
                    {t.trackingPage.badgeReturnPickup}
                  </div>
                )}
                {/* The page's only heading: a screen reader landing here otherwise finds no title at all. */}
                <h1 style={{ fontSize: 22, fontWeight: 800, color: INK.strong, lineHeight: 1.1, margin: 0 }}>{st.label}</h1>
                <div style={{ fontSize: 13, color: INK.muted, marginTop: 4 }}>{st.sub}</div>
              </div>
              {eta && (
                <div style={{ textAlign: 'right', flexShrink: 0 }}>
                  <div style={{ fontSize: 10, fontWeight: 600, color: INK.muted, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                    {t.trackingPage.labelEta}
                  </div>
                  <div style={{ fontSize: 20, fontWeight: 800, color: st.color, fontFamily: 'monospace', lineHeight: 1.1 }}>{eta}</div>
                  {etaDay && <div style={{ fontSize: 11, fontWeight: 600, color: INK.body, marginTop: 2 }}>{etaDay}</div>}
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
              /*
                An ordered list, not four styled divs.

                Progress was carried entirely by colour and a check glyph: to a screen reader the
                stepper read as four bare words with no order, no current position and no sense of
                which were behind us. `aria-current="step"` marks where we are, and each label
                carries its state in text — the WCAG 1.4.1 rule that colour may not be the only
                channel for meaning, which is exactly what "reached = tinted" was.
              */
              <>
              <ol
                style={{ display: 'flex', alignItems: 'flex-start', marginTop: 22, listStyle: 'none', margin: '22px 0 0', padding: 0 }}
                aria-label={t.trackingPage.stepperLabel}
              >
                {STEPS.map((step, i) => {
                  const reached = stepIdx >= i;   // node coloured
                  const done    = stepIdx > i;    // past → check
                  const active  = stepIdx === i;  // current → icon + pulse
                  const StepIcon = STEP_ICONS[i];
                  const stateText = done ? t.trackingPage.stepStateDone
                    : active ? t.trackingPage.stepStateCurrent
                    : t.trackingPage.stepStateUpcoming;
                  return (
                    <li
                      key={step}
                      style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center' }}
                      aria-current={active ? 'step' : undefined}
                    >
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
                            : <StepIcon size={15} color={reached ? '#fff' : INK.faint} stroke={2} />}
                        </div>
                        <div style={{ flex: 1, height: 3, borderRadius: 2, background: (i < STEPS.length - 1 && stepIdx > i) ? st.color : '#e5e7eb', transition: 'background 0.3s' }} />
                      </div>
                      <div style={{ fontSize: 11, fontWeight: active ? 800 : 600, color: active ? st.color : reached ? INK.body : INK.faint, marginTop: 8, textAlign: 'center', lineHeight: 1.2 }}>
                        {getStepLabels(t)[i]}
                        <span style={SR_ONLY}> — {stateText}</span>
                      </div>
                    </li>
                  );
                })}
              </ol>
              <style>{`@keyframes trkPulse{0%{transform:scale(1);opacity:0.22}100%{transform:scale(1.7);opacity:0}}@media (prefers-reduced-motion:reduce){[style*="trkPulse"]{animation:none!important}}`}</style>
              </>
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
                  <div style={{ fontSize: 11, color: INK.muted, fontWeight: 600 }}>{t.trackingPage.labelDriver}</div>
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
              <div style={{ fontSize: 11, fontWeight: 700, color: INK.muted }}>
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
                        <span style={{ fontSize: 10, color: INK.muted, fontWeight: 400 }}> {t.trackingPage.currencyTnd}</span>
                      </span>
                    )}
                  </div>
                ))}

                {data.totalAmount != null && (
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingTop: 10, borderTop: '1px solid #f1f5f9', marginTop: 4 }}>
                    <span style={{ fontSize: 12, fontWeight: 600, color: INK.muted }}>{t.trackingPage.labelTotal}</span>
                    <span style={{ fontSize: 17, fontWeight: 800, color: '#0f172a', fontFamily: 'monospace' }}>
                      {fmtAmount(data.totalAmount)}
                      <span style={{ fontSize: 11, fontWeight: 500, color: INK.muted }}> {t.trackingPage.currencyTnd}</span>
                    </span>
                  </div>
                )}
              </div>
            ) : (
              <p style={{ fontSize: 13, color: INK.muted, margin: 0 }}>{t.trackingPage.noItems}</p>
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
            <div style={{ fontSize: 11, fontWeight: 700, color: INK.muted, marginBottom: 8 }}>
              {t.trackingPage.sectionAddress}
            </div>
            <div style={{ fontSize: 14, fontWeight: 700, color: '#0f172a' }}>{data.clientName}</div>
            {data.dropoffAddress && <div style={{ fontSize: 12, color: '#64748b', marginTop: 3, lineHeight: 1.5 }}>{data.dropoffAddress}</div>}
            {data.dropoffCity && <div style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginTop: 2 }}>{data.dropoffCity}</div>}
          </div>

          {/* Window */}
          {(data.startWindow || data.endWindow) && (
            <div style={{ padding: '16px 20px', borderBottom: '1px solid #f1f5f9', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: INK.muted }}>
                {t.trackingPage.sectionSlot}
              </div>
              <div style={{ fontSize: 15, fontWeight: 800, color: '#0f172a', fontFamily: 'monospace' }}>
                {data.startWindow ?? '—'} – {data.endWindow ?? '—'}
              </div>
            </div>
          )}

          <div style={{ padding: '16px 20px', textAlign: 'center' }}>
            <span style={{ fontSize: 11, color: INK.muted }}>{t.trackingPage.liveTracking}{data.companyName ?? t.trackingPage.appTitle}</span>
          </div>
        </div>
      </div>
    </div>
    </div>
  );
}
