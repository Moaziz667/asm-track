
type StopPoint = {
  deliveryId: string;
  stopOrder: number;
  status?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  deliveryAddress?: string;
};

type DriverPoint = {
  name?: string;
  lat?: number;
  lng?: number;
  show?: boolean;
};

type Props = {
  stops: StopPoint[];
  height?: number;
  driver?: DriverPoint;
};

function hasCoordinates(stop: StopPoint): boolean {
  return typeof stop.dropoffLat === 'number' && typeof stop.dropoffLng === 'number';
}

export default function RouteStopsLiveMap({ stops, height = 180, driver }: Props) {
  const pinnedStops = stops.filter(hasCoordinates);

  return (
    <div
      style={{
        height,
        border: '1px solid #e5e7eb',
        borderRadius: 10,
        background: 'linear-gradient(180deg, #f8fafc 0%, #eef2ff 100%)',
        padding: 10,
        overflow: 'auto',
      }}
    >
      <div style={{ fontSize: 11, color: '#475569', marginBottom: 8, fontWeight: 700 }}>
        Carte live simplifiee
      </div>

      {driver?.show && (
        <div style={{ fontSize: 12, color: '#1d4ed8', marginBottom: 8 }}>
          Chauffeur: {driver.name || 'N/A'} {driver.lat != null && driver.lng != null ? `(${driver.lat.toFixed(4)}, ${driver.lng.toFixed(4)})` : '(position indisponible)'}
        </div>
      )}

      {pinnedStops.length === 0 ? (
        <div style={{ fontSize: 12, color: '#6b7280' }}>Aucun stop epingle pour cette tournee.</div>
      ) : (
        <div style={{ display: 'grid', gap: 6 }}>
          {pinnedStops.map((stop) => (
            <div key={stop.deliveryId} style={{ fontSize: 12, color: '#111827', background: '#fff', border: '1px solid #dbeafe', borderRadius: 8, padding: '6px 8px' }}>
              Stop #{stop.stopOrder || 0} - {stop.deliveryAddress || stop.deliveryId}
              <div style={{ color: '#4b5563', marginTop: 2 }}>
                {stop.dropoffLat?.toFixed(5)}, {stop.dropoffLng?.toFixed(5)}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

