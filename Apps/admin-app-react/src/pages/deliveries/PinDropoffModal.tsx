import { useEffect, useRef, useState, lazy as dynamic } from 'react';
import { api } from '@/lib/api';
import { useT } from '@/lib/i18n/LocaleContext';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { AppModal } from '@/components/overlays/AppModal';
import { AppLoader } from '@/components/AppLoader';
import { FieldInput } from '@/components/ui/field';
import { IconSearch, IconAlertCircle } from '@tabler/icons-react';
import type { GeocodeSuggestion } from '@/types';
import { usePinDropoff } from '@/hooks/useDeliveries';
import { Spinner } from './helpers';
import { cleanTunisianAdminName } from '@/lib/utils/address';
import type { DeliveryRow } from './types';

const RouteTrackingMap = dynamic(() => import('@/components/RouteTrackingMap'));

const LOCKED_STATUSES = ['PICKED_UP', 'IN_TRANSIT', 'AWAITING_HANDOFF', 'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED'];

/**
 * Geo-pinning of a delivery dropoff: map picker + Nominatim address search + reverse geocoding.
 * Self-contained — owns all pin/geocode/search state. Opens whenever `target` is set; the parent
 * just toggles `target` and reacts to `onPinned` (e.g. flag the route for refresh).
 */
export function PinDropoffModal({
  target, onClose, onPinned,
}: {
  target: DeliveryRow | null;
  onClose: () => void;
  onPinned?: (routeId?: string) => void;
}) {
  const t = useT();
  const pinDropoffMutation = usePinDropoff();
  const pinSaving = pinDropoffMutation.isPending;

  const [pinLat, setPinLat] = useState<number | null>(null);
  const [pinLng, setPinLng] = useState<number | null>(null);
  const [pinAddress, setPinAddress] = useState('');
  const [pinCity, setPinCity] = useState('');
  const [pinPostalCode, setPinPostalCode] = useState('');
  const [reverseGeocoding, setReverseGeocoding] = useState(false);
  const [addressSearch, setAddressSearch] = useState('');
  const [addressResults, setAddressResults] = useState<Array<{ lat: string; lon: string; display_name: string }>>([]);
  const [showAddressResults, setShowAddressResults] = useState(false);
  const [searchingAddress, setSearchingAddress] = useState(false);
  const [flyCenter, setFlyCenter] = useState<[number, number] | undefined>(undefined);
  const searchDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const locked = !!target && LOCKED_STATUSES.includes(target.status);

  // Initialize (and geocode if needed) each time a new target opens — mirrors the old openPinModal.
  useEffect(() => {
    if (!target) return;
    let alive = true;
    setAddressSearch(''); setAddressResults([]); setShowAddressResults(false); setSearchingAddress(false);
    setFlyCenter(target.dropoffLat && target.dropoffLng ? [target.dropoffLat, target.dropoffLng] : undefined);

    if (target.dropoffPinned && target.dropoffLat != null) {
      setPinLat(target.dropoffLat ?? null); setPinLng(target.dropoffLng ?? null);
      setPinAddress(target.dropoffAddress ?? ''); setPinCity(target.dropoffCity ?? '');
      setPinPostalCode(target.dropoffPostalCode ?? '');
      return;
    }

    setPinLat(null); setPinLng(null); setPinAddress(target.dropoffAddress ?? ''); setPinCity(target.dropoffCity ?? ''); setPinPostalCode('');
    if (!LOCKED_STATUSES.includes(target.status)) {
      (async () => {
        try {
          const res = await api.get(`/admin/deliveries/${target.rowId}/geocode`);
          const sug: GeocodeSuggestion = res.data;
          if (alive && sug.found) {
            setPinLat(sug.lat ?? null); setPinLng(sug.lng ?? null);
            if (!target.dropoffAddress) setPinAddress(sug.displayName ?? '');
            if (sug.postalCode) setPinPostalCode(sug.postalCode);
          }
        } catch { /* leave unpinned */ }
      })();
    }
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.rowId]);

  const handleMapPick = async (lat: number, lng: number) => {
    setPinLat(lat); setPinLng(lng); setReverseGeocoding(true);
    try {
      const res = await api.get('/admin/deliveries/reverse-geocode', { params: { lat, lng } });
      const rev: GeocodeSuggestion = res.data;
      if (rev.found) {
        setPinAddress(rev.displayName ?? '');
        if (rev.city) setPinCity(cleanTunisianAdminName(rev.city));
        if (rev.postalCode) setPinPostalCode(rev.postalCode);
      }
    } catch { /* keep manual */ } finally { setReverseGeocoding(false); }
  };

  const handleAddressSearch = async (q: string) => {
    setAddressSearch(q);
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current);
    if (q.trim().length < 3) { setAddressResults([]); setShowAddressResults(false); return; }
    searchDebounceRef.current = setTimeout(async () => {
      setSearchingAddress(true);
      try {
        const url = `https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=${encodeURIComponent(q)}&countrycodes=tn`;
        const res = await fetch(url);
        const data = await res.json();
        setAddressResults(data);
        setShowAddressResults(data && data.length > 0);
      } catch {
        setAddressResults([]); setShowAddressResults(false);
      } finally { setSearchingAddress(false); }
    }, 400);
  };

  const pickAddressSuggestion = (r: { lat: string; lon: string; display_name: string }) => {
    setFlyCenter([parseFloat(r.lat), parseFloat(r.lon)]);
    setAddressSearch(r.display_name.split(',')[0]);
    setShowAddressResults(false);
    showSuccessToast(t.deliveriesPage.addressLocated);
  };

  const confirmPin = async () => {
    if (!target || pinLat == null || pinLng == null) return;
    try {
      await pinDropoffMutation.mutateAsync({
        deliveryId: target.rowId,
        payload: { lat: pinLat, lng: pinLng, dropoffAddress: pinAddress, dropoffCity: pinCity, dropoffPostalCode: pinPostalCode },
      });
      showSuccessToast(t.deliveriesPage.pinModalConfirm);
      onPinned?.(target.routeId);
      onClose();
    } catch (err) { showErrorToast(err); }
  };

  return (
    <AppModal
      opened={!!target}
      onClose={onClose}
      size="lg"
      title={
        <div className="flex items-center justify-between w-full gap-4 flex-nowrap">
          <div className="flex flex-col gap-0">
            <span className="text-lg font-[700] tracking-tight text-[var(--text-primary)]">
              {locked ? t.deliveriesPage.lockedGeocoding : t.deliveriesPage.pinModalTitle}
            </span>
          </div>
          {!locked && (
            <div className="relative w-[280px]">
              <FieldInput
                placeholder={t.deliveriesPage.pinModalSearch}
                leftSection={<IconSearch size={14} className="text-[var(--text-muted)]" />}
                rightSection={searchingAddress ? <Spinner className="h-3 w-3" /> : undefined}
                value={addressSearch}
                onChange={(e) => handleAddressSearch(e.currentTarget.value)}
                onFocus={() => addressResults.length > 0 && setShowAddressResults(true)}
                onBlur={() => setTimeout(() => setShowAddressResults(false), 200)}
                className="h-8 text-xs"
              />
              {showAddressResults && (
                <div className="absolute top-full left-0 right-0 z-[1001] bg-[var(--surface)] border border-[var(--border)] rounded mt-1 shadow-xl max-h-[200px] overflow-y-auto">
                  <div className="flex flex-col gap-0">
                    {addressResults.map((r, idx) => (
                      <div
                        key={idx}
                        className="px-3 py-2 hover:bg-[var(--surface)] cursor-pointer border-b border-[var(--border)] last:border-0"
                        onClick={() => pickAddressSuggestion(r)}
                      >
                        <span className="text-sm font-[500] text-[var(--text-primary)] line-clamp-1">{r.display_name}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      }
    >
      <div className="flex flex-col gap-0">
        {locked && (
          <div className="p-3 bg-[var(--brand-soft)] border-b border-[var(--brand)]/20 mb-3 rounded">
            <div className="flex items-start gap-2 flex-nowrap">
              <IconAlertCircle size={18} className="text-[var(--brand)] shrink-0" />
              <span className="text-sm font-[500] text-[var(--brand)] leading-relaxed italic">
                {t.deliveriesPage.lockedDeliveryMessage}
              </span>
            </div>
          </div>
        )}

        <div className="relative">
          <RouteTrackingMap
            stops={[]}
            height={400}
            pinLat={pinLat} pinLng={pinLng}
            onPick={!locked ? handleMapPick : undefined}
            center={flyCenter}
            zoom={flyCenter ? 16 : 12}
          />
          {reverseGeocoding && (
            <div className="absolute inset-0 bg-white/40 flex items-center justify-center z-50">
              <div className="flex items-center gap-2 bg-[var(--surface)] px-6 py-2 rounded-xs shadow-xl border border-[var(--border)]">
                <AppLoader size="sm" />
                <span className="text-sm font-[500] text-[var(--text-primary)]">{t.deliveriesPage.analyzeInProgress}</span>
              </div>
            </div>
          )}
        </div>

        {!locked ? (
          <div className="p-6 bg-[var(--surface)] border-t border-[var(--border)]">
            <div className="grid grid-cols-[1fr_auto] gap-3">
              <FieldInput
                label={<span className="text-xs font-[500] text-[var(--text-muted)]">{t.deliveriesPage.addressTarget}</span>}
                placeholder={t.deliveriesPage.addressPlaceholder}
                value={pinAddress}
                onChange={(e) => setPinAddress(e.currentTarget.value)}
                className="h-10 font-[600]"
              />
              <FieldInput
                label={<span className="text-xs font-[500] text-[var(--text-muted)]">{t.deliveriesPage.postalCodeLabel}</span>}
                placeholder={t.deliveriesPage.postalCodePlaceholder}
                value={pinPostalCode}
                onChange={(e) => setPinPostalCode(e.currentTarget.value)}
                className="h-10 font-[700] font-mono w-28"
              />
            </div>

            <div className="flex items-center gap-3 mt-6">
              <button
                type="button"
                className="flex-1 h-10 font-[500] hover:bg-[var(--hover-bg)] text-[var(--text-soft)] text-sm border border-[var(--border)] rounded transition-colors"
                onClick={onClose}
              >
                {t.actions.cancel}
              </button>
              <button
                type="button"
                className="flex-1 h-10 bg-[var(--brand)] hover:opacity-90 text-white font-[600] text-sm rounded flex items-center justify-center gap-2 whitespace-nowrap transition-colors disabled:bg-[var(--brand-soft)] disabled:text-[var(--brand)] disabled:cursor-not-allowed"
                onClick={confirmPin}
                disabled={pinLat == null || pinSaving}
              >
                {pinSaving ? <Spinner className="h-3 w-3 text-white" /> : null}
                {t.deliveriesPage.pinButtonConfirm}
              </button>
            </div>
          </div>
        ) : (
          <div className="p-6 bg-[var(--surface)] border-t border-[var(--border)]">
            <button
              type="button"
              className="w-full h-10 border border-[var(--border)] text-[var(--text-primary)] font-[500] text-sm rounded hover:bg-[var(--hover-bg)] transition-colors"
              onClick={onClose}
            >
              {t.actions.close}
            </button>
          </div>
        )}
      </div>
    </AppModal>
  );
}
