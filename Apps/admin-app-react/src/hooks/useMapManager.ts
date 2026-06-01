
import * as React from 'react';
import L from 'leaflet';

type DriverMarkerMap = { [driverId: string]: L.Marker };
type RoutePolylineMap = { [routeId: string]: L.Polyline };

export function useMapManager(mapInstance: L.Map | null) {
  const driverMarkersRef = React.useRef<DriverMarkerMap>({});
  const routesLayerRef = React.useRef<RoutePolylineMap>({});
  const layerGroupRef = React.useRef<L.LayerGroup | null>(null);

  // Initialize unified layer group once the map instance is ready
  React.useEffect(() => {
    if (!mapInstance) return;

    // Create a canvas-based layer group for hardware acceleration
    layerGroupRef.current = L.layerGroup().addTo(mapInstance);

    return () => {
      layerGroupRef.current?.remove();
      driverMarkersRef.current = {};
      routesLayerRef.current = {};
    };
  }, [mapInstance]);

  // Imperative Driver Telemetry Update (Bypasses React reconciliation cycles)
  const updateDriverPosition = React.useCallback(
    (driverId: string, lat: number, lng: number, name: string, status: 'active' | 'muted') => {
      if (!mapInstance || !layerGroupRef.current) return;

      const existingMarker = driverMarkersRef.current[driverId];
      const accentColor = status === 'active' ? '#3b82f6' : '#94a3b8';

      // Reusable HTML marker utilizing custom Geist HSL state variables
      const customIcon = L.divIcon({
        className: 'custom-driver-marker-indicator',
        iconSize: [28, 28],
        iconAnchor: [14, 14],
        html: `
          <div style="position: relative; width: 28px; height: 28px; display: flex; items-center; justify-content: center;">
            <!-- Outer ripple pulse ring for active drivers -->
            ${status === 'active' 
              ? `<div style="position: absolute; inset: 0; background-color: ${accentColor}; border-radius: 50%; opacity: 0.15; transform: scale(1.4); animation: ping 1.8s cubic-bezier(0, 0, 0.2, 1) infinite;"></div>` 
              : ''
            }
            <!-- Solid core indicator dot -->
            <div style="width: 12px; height: 12px; border-radius: 50%; background-color: ${accentColor}; border: 2px solid var(--bg-panel); box-shadow: 0 1px 3px rgba(0,0,0,0.3); z-index: 10; margin: auto;"></div>
          </div>
        `,
      });

      if (existingMarker) {
        // Direct imperative update - 60 FPS movement
        existingMarker.setLatLng([lat, lng]);
        existingMarker.setIcon(customIcon);
      } else {
        // Instantiate and cache marker inside our refs block
        const newMarker = L.marker([lat, lng], { icon: customIcon })
          .addTo(layerGroupRef.current)
          .bindPopup(`
            <div style="font-family: inherit; font-size: 11px; padding: 4px; min-width: 100px;">
              <strong style="color: var(--text-primary); font-weight: 700;">${name}</strong>
              <div style="color: var(--text-muted); font-size: 10px; margin-top: 2px;">
                Chauffeur · ${status === 'active' ? 'En ligne' : 'Inactif'}
              </div>
            </div>
          `);
        
        driverMarkersRef.current[driverId] = newMarker;
      }
    },
    [mapInstance]
  );

  // Imperative Route Drawing methods
  const drawRoutePath = React.useCallback(
    (routeId: string, coordinates: [number, number][], color = '#3b82f6') => {
      if (!mapInstance || !layerGroupRef.current || coordinates.length < 2) return;

      // Remove legacy polyline if it exists
      const oldPolyline = routesLayerRef.current[routeId];
      if (oldPolyline) {
        oldPolyline.remove();
      }

      // Draw polyline directly inside hardware-accelerated canvas layer
      const polyline = L.polyline(coordinates, {
        color,
        weight: 3.5,
        opacity: 0.8,
        lineJoin: 'round',
      }).addTo(layerGroupRef.current);

      routesLayerRef.current[routeId] = polyline;
    },
    [mapInstance]
  );

  const clearRoutePath = React.useCallback((routeId: string) => {
    const polyline = routesLayerRef.current[routeId];
    if (polyline) {
      polyline.remove();
      delete routesLayerRef.current[routeId];
    }
  }, []);

  const focusOnCoordinates = React.useCallback(
    (lat: number, lng: number, zoom = 15) => {
      if (!mapInstance) return;
      try {
        mapInstance.flyTo([lat, lng], zoom, {
          animate: true,
          duration: 0.75,
        });
      } catch {
        // Ignore boundary overflows
      }
    },
    [mapInstance]
  );

  return {
    updateDriverPosition,
    drawRoutePath,
    clearRoutePath,
    focusOnCoordinates,
  };
}
