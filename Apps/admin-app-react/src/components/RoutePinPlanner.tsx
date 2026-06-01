
type PlannerStop = {
  deliveryId: string;
  clientName?: string;
  dropoffAddress?: string;
  dropoffCity?: string;
  lat?: number;
  lng?: number;
};

type Props = {
  stops: PlannerStop[];
  activeStopDeliveryId: string | null;
  onMapPick: (lat: number, lng: number) => void;
};

const TUNIS_BOUNDS = {
  minLat: 35.5,
  maxLat: 37.4,
  minLng: 8.0,
  maxLng: 11.9,
};

function randomInRange(min: number, max: number): number {
  return min + Math.random() * (max - min);
}

export default function RoutePinPlanner({ stops, activeStopDeliveryId, onMapPick }: Props) {
  const activeStop = stops.find((stop) => stop.deliveryId === activeStopDeliveryId) ?? null;

  const pinRandomTunisiaPoint = () => {
    onMapPick(randomInRange(TUNIS_BOUNDS.minLat, TUNIS_BOUNDS.maxLat), randomInRange(TUNIS_BOUNDS.minLng, TUNIS_BOUNDS.maxLng));
  };

  return (
    <div style={{ border: '1px solid #e5e7eb', borderRadius: 8, padding: 10, background: '#f8fafc' }}>
      <div style={{ fontSize: 12, fontWeight: 700, color: '#111827', marginBottom: 4 }}>
        Planificateur de pin
      </div>
      <div style={{ fontSize: 12, color: '#4b5563', marginBottom: 10 }}>
        Selectionnez un stop puis placez un pin via les actions ci-dessous.
      </div>

      {activeStop ? (
        <div style={{ fontSize: 12, color: '#111827', marginBottom: 10, background: '#fff', border: '1px solid #d1d5db', borderRadius: 6, padding: '8px 10px' }}>
          <div style={{ fontWeight: 700 }}>{activeStop.clientName || 'Client'} - {activeStop.deliveryId}</div>
          <div style={{ color: '#6b7280', marginTop: 2 }}>{activeStop.dropoffAddress || 'Adresse non disponible'} {activeStop.dropoffCity ? `(${activeStop.dropoffCity})` : ''}</div>
          <div style={{ marginTop: 4, color: '#334155' }}>
            Coordonnees actuelles: {activeStop.lat != null && activeStop.lng != null ? `${activeStop.lat}, ${activeStop.lng}` : 'non epinglees'}
          </div>
        </div>
      ) : (
        <div style={{ fontSize: 12, color: '#9ca3af', marginBottom: 10 }}>Aucun stop actif selectionne.</div>
      )}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button
          onClick={pinRandomTunisiaPoint}
          disabled={!activeStop}
          style={{
            height: 32,
            border: '1px solid #93c5fd',
            borderRadius: 6,
            background: activeStop ? '#eff6ff' : '#f3f4f6',
            color: activeStop ? '#1d4ed8' : '#9ca3af',
            padding: '0 10px',
            cursor: activeStop ? 'pointer' : 'not-allowed',
            fontSize: 12,
            fontWeight: 700,
          }}
        >
          Pin aleatoire (Tunisie)
        </button>
        <button
          onClick={() => onMapPick(36.8065, 10.1815)}
          disabled={!activeStop}
          style={{
            height: 32,
            border: '1px solid #86efac',
            borderRadius: 6,
            background: activeStop ? '#ecfdf5' : '#f3f4f6',
            color: activeStop ? '#166534' : '#9ca3af',
            padding: '0 10px',
            cursor: activeStop ? 'pointer' : 'not-allowed',
            fontSize: 12,
            fontWeight: 700,
          }}
        >
          Pin centre Tunis
        </button>
      </div>
    </div>
  );
}

