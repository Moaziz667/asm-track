import { useState, useEffect, useCallback } from 'react';
import { MapContainer, TileLayer, Marker, useMapEvents, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { IconMapPin, IconX, IconCheck, IconCurrentLocation } from '@tabler/icons-react';
import { usePatchDepotLocation } from '@/hooks/useDepots';
import type { Depot } from '@/types';
import { api } from '@/lib/api';

import { useIsDark } from '@/lib/theme';

// ── Draggable marker that also responds to map clicks ──────────────────────────

function PickerMarker({
  position,
  onChange,
}: {
  position: [number, number];
  onChange: (lat: number, lng: number) => void;
}) {
  const icon = L.divIcon({
    className: '',
    iconSize: [32, 42],
    iconAnchor: [16, 40],
    html: `<div style="position:relative;width:32px;height:42px;">
      <div style="position:absolute;left:50%;top:0;transform:translateX(-50%);width:28px;height:28px;border-radius:50%;background:#3B82F6;border:3px solid #fff;box-shadow:0 2px 8px rgba(59,130,246,0.5);"></div>
      <div style="position:absolute;left:50%;top:22px;transform:translateX(-50%);width:0;height:0;border-left:7px solid transparent;border-right:7px solid transparent;border-top:16px solid #3B82F6;"></div>
    </div>`,
  });

  useMapEvents({
    click(e) {
      onChange(e.latlng.lat, e.latlng.lng);
    },
  });

  return (
    <Marker
      position={position}
      icon={icon}
      draggable
      eventHandlers={{
        dragend(e) {
          const latlng = (e.target as L.Marker).getLatLng();
          onChange(latlng.lat, latlng.lng);
        },
      }}
    />
  );
}

function MapCenterer({ position }: { position: [number, number] }) {
  const map = useMap();
  useEffect(() => {
    map.setView(position, map.getZoom());
  }, [map, position]);
  return null;
}

// ── Modal ──────────────────────────────────────────────────────────────────────

interface Props {
  depot: Depot;
  onClose: () => void;
}

export default function DepotLocationModal({ depot, onClose }: Props) {
  const patch = usePatchDepotLocation();
  const isDark = useIsDark();

  const defaultLat = depot.latitude ?? 36.8065;
  const defaultLng = depot.longitude ?? 10.1815;

  const [lat, setLat] = useState<string>(String(defaultLat));
  const [lng, setLng] = useState<string>(String(defaultLng));
  const [address, setAddress] = useState(depot.address ?? '');
  const [mapCenter, setMapCenter] = useState<[number, number]>([defaultLat, defaultLng]);
  const [recenter, setRecenter] = useState(false);

  const markerPos: [number, number] = [
    parseFloat(lat) || defaultLat,
    parseFloat(lng) || defaultLng,
  ];

  const [reverseGeocoding, setReverseGeocoding] = useState(false);

  const onMapPick = useCallback(async (pickedLat: number, pickedLng: number) => {
    setLat(pickedLat.toFixed(6));
    setLng(pickedLng.toFixed(6));
    try {
      setReverseGeocoding(true);
      const res = await api.get<{ displayName?: string }>('/api/admin/deliveries/reverse-geocode', {
        params: { lat: pickedLat, lng: pickedLng }
      });
      if (res.data?.displayName) {
        setAddress(res.data.displayName);
      }
    } catch (err) {
      console.error('Failed to reverse geocode location:', err);
    } finally {
      setReverseGeocoding(false);
    }
  }, []);

  const handleRecenter = () => {
    setMapCenter(markerPos);
    setRecenter((v) => !v);
  };

  const handleSave = () => {
    const parsedLat = parseFloat(lat);
    const parsedLng = parseFloat(lng);
    if (isNaN(parsedLat) || isNaN(parsedLng)) return;
    patch.mutate(
      { depotId: depot.id, payload: { latitude: parsedLat, longitude: parsedLng, address: address.trim() || undefined } },
      { onSuccess: onClose }
    );
  };

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center"
      style={{ background: 'rgba(0,0,0,0.55)', backdropFilter: 'blur(2px)' }}
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        className="w-full max-w-2xl rounded-xl overflow-hidden flex flex-col"
        style={{ background: 'var(--surface)', border: '1px solid var(--border)', maxHeight: '90vh' }}
      >
        {/* Header */}
        <div
          className="flex items-center justify-between px-5 py-3.5"
          style={{ borderBottom: '1px solid var(--border)', background: 'var(--app-bg)' }}
        >
          <div className="flex items-center gap-2.5">
            <IconMapPin size={16} style={{ color: 'var(--brand)' }} />
            <div>
              <p className="text-xs font-bold" style={{ color: 'var(--text-primary)' }}>
                {depot.name}
              </p>
              <p className="text-2xs" style={{ color: 'var(--text-muted)' }}>Modifier la localisation</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="w-7 h-7 flex items-center justify-center rounded-md hover:bg-[var(--hover-bg)] transition-colors"
          >
            <IconX size={14} style={{ color: 'var(--text-muted)' }} />
          </button>
        </div>

        {/* Map */}
        <div style={{ height: 320, position: 'relative', zIndex: 1 }}>
          <MapContainer
            center={mapCenter}
            zoom={13}
            style={{ height: '100%', width: '100%' }}
            key={`map-${depot.id}`}
          >
            <TileLayer
              attribution={isDark ? '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>' : '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'}
              url={isDark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' : 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'}
            />
            <PickerMarker position={markerPos} onChange={onMapPick} />
            {recenter && <MapCenterer position={mapCenter} />}
          </MapContainer>
          <div
            className="absolute bottom-3 left-3 z-[1000] px-2.5 py-1.5 rounded-md text-2xs font-medium"
            style={{ background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text-muted)' }}
          >
            Cliquez sur la carte ou déplacez le marqueur
          </div>
        </div>

        {/* Fields */}
        <div className="flex flex-col gap-4 p-5" style={{ overflowY: 'auto' }}>
          {/* Lat / Lng row */}
          <div className="flex gap-3">
            <div className="flex-1 flex flex-col gap-1">
              <label className="text-2xs font-[700] uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                Latitude
              </label>
              <input
                type="number"
                step="0.000001"
                value={lat}
                onChange={(e) => setLat(e.target.value)}
                className="w-full px-3 py-2 rounded-md text-sm font-mono outline-none transition-colors"
                style={{
                  background: 'var(--app-bg)',
                  border: '1px solid var(--border)',
                  color: 'var(--text-primary)',
                }}
              />
            </div>
            <div className="flex-1 flex flex-col gap-1">
              <label className="text-2xs font-[700] uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                Longitude
              </label>
              <input
                type="number"
                step="0.000001"
                value={lng}
                onChange={(e) => setLng(e.target.value)}
                className="w-full px-3 py-2 rounded-md text-sm font-mono outline-none transition-colors"
                style={{
                  background: 'var(--app-bg)',
                  border: '1px solid var(--border)',
                  color: 'var(--text-primary)',
                }}
              />
            </div>
            <div className="flex items-end">
              <button
                onClick={handleRecenter}
                title="Centrer la carte sur le marqueur"
                className="h-9 w-9 flex items-center justify-center rounded-md transition-colors"
                style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-muted)' }}
              >
                <IconCurrentLocation size={15} />
              </button>
            </div>
          </div>

          {/* Address */}
          <div className="flex flex-col gap-1">
            <label className="text-2xs font-[700] uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
              Adresse
            </label>
            <input
              type="text"
              value={reverseGeocoding ? 'Localisation en cours...' : address}
              onChange={(e) => setAddress(e.target.value)}
              disabled={reverseGeocoding}
              placeholder="Adresse du dépôt…"
              className="w-full px-3 py-2 rounded-md text-sm outline-none transition-colors disabled:opacity-60"
              style={{
                background: 'var(--app-bg)',
                border: '1px solid var(--border)',
                color: 'var(--text-primary)',
              }}
            />
          </div>

          {/* Actions */}
          <div className="flex items-center justify-end gap-2 pt-1">
            <button
              onClick={onClose}
              className="px-4 py-2 rounded-md text-xs font-bold transition-colors"
              style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-muted)' }}
            >
              Annuler
            </button>
            <button
              onClick={handleSave}
              disabled={patch.isPending}
              className="flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold transition-opacity disabled:opacity-60"
              style={{ background: 'var(--brand)', color: '#fff', border: 'none' }}
            >
              {patch.isPending ? (
                <svg className="animate-spin" width={13} height={13} viewBox="0 0 24 24" fill="none">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
                </svg>
              ) : (
                <IconCheck size={13} />
              )}
              Enregistrer
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
