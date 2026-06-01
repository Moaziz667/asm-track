
type DeliveryPinMapProps = {
  lat?: number;
  lng?: number;
  isSuggestion?: boolean;
  locked?: boolean;
  onPick?: (lat: number, lng: number) => void;
  height?: number;
};

export default function DeliveryPinMap({ lat, lng, isSuggestion, locked, onPick, height = 300 }: DeliveryPinMapProps) {
  return (
    <div
      onClick={(event) => {
        if (locked) return;
        if (!onPick) return;
        const rect = event.currentTarget.getBoundingClientRect();
        const xRatio = (event.clientX - rect.left) / rect.width;
        const yRatio = (event.clientY - rect.top) / rect.height;
        const pickedLat = 30 + (1 - yRatio) * 8;
        const pickedLng = 7 + xRatio * 5;
        onPick(Number(pickedLat.toFixed(6)), Number(pickedLng.toFixed(6)));
      }}
      style={{
        height,
        border: '1px solid #d8e2ee',
        borderRadius: 8,
        background: 'linear-gradient(145deg, #f8fafc 0%, #f1f5f9 100%)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: '#475569',
        fontSize: 12,
        cursor: locked ? 'not-allowed' : 'crosshair',
        textAlign: 'center',
        padding: 12,
        position: 'relative',
        overflow: 'hidden'
      }}
    >
      {/* Small Map Texture effect */}
      <div style={{ position: 'absolute', inset: 0, opacity: 0.1, backgroundImage: 'radial-gradient(#94a3b8 1px, transparent 1px)', backgroundSize: '20px 20px' }} />

      <div style={{ position: 'relative', zIndex: 1 }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
          <div style={{ width: 40, height: 40, background: '#fff', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', boxShadow: '0 2px 8px rgba(0,0,0,0.08)' }}>
             <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
                <circle cx="12" cy="10" r="3"></circle>
             </svg>
          </div>
          <div>
            <div style={{ fontWeight: 800, fontSize: 13, color: '#111827' }}>Click to place a pin</div>
            <div style={{ color: '#64748b', fontSize: 11 }}>Locate the client address on the map</div>
          </div>
        </div>

        {lat != null && lng != null && (
          <div style={{ marginTop: 12, background: '#fff', padding: '6px 12px', borderRadius: 99, border: '1px solid #e2e8f0', display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 700, color: '#0f172a', boxShadow: '0 2px 4px rgba(0,0,0,0.04)' }}>
            <span style={{ width: 6, height: 6, background: '#22c55e', borderRadius: '50%' }} />
            {lat.toFixed(5)}, {lng.toFixed(5)}
          </div>
        )}
        
        {isSuggestion && (
          <div style={{ color: '#166534', marginTop: 8, fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.02em', background: '#dcfce7', padding: '2px 8px', borderRadius: 4, display: 'inline-block' }}>
            Suggested by geocoder
          </div>
        )}
      </div>
    </div>
  );
}

