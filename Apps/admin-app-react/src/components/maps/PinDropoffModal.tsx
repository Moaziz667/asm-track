import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { CircleMarker, MapContainer, TileLayer, useMap, useMapEvents } from 'react-leaflet'
import type { LatLngExpression, LeafletMouseEvent } from 'leaflet'
import { geocodeDelivery, getAdminDeliveryDetail, pinDropoff, reverseGeocode } from '../../services/deliveryAdmin'

type Props = {
  deliveryId: string | null
  onClose: () => void
  onPinned: () => void
}

type ClickListenerProps = {
  onClick: (lat: number, lng: number) => void
}

function ClickListener({ onClick }: ClickListenerProps) {
  useMapEvents({
    click(event: LeafletMouseEvent) {
      onClick(event.latlng.lat, event.latlng.lng)
    },
  })
  return null
}

function FlyToMarker({ position }: { position: LatLngExpression | null }) {
  const map = useMap()
  useEffect(() => {
    if (position) map.flyTo(position, Math.max(map.getZoom(), 14), { duration: 0.8 })
  }, [position, map])
  return null
}

export function PinDropoffModal({ deliveryId, onClose, onPinned }: Props) {
  const isOpen = Boolean(deliveryId)

  const detailQuery = useQuery({
    queryKey: ['delivery-detail-pin', deliveryId],
    queryFn: () => getAdminDeliveryDetail(deliveryId as string),
    enabled: isOpen,
  })

  const geocodeMutation = useMutation({
    mutationFn: () => geocodeDelivery(deliveryId as string),
  })

  const reverseMutation = useMutation({
    mutationFn: ({ lat, lng }: { lat: number; lng: number }) => reverseGeocode(lat, lng),
  })

  const pinMutation = useMutation({
    mutationFn: (payload: {
      lat: number
      lng: number
      dropoffAddress?: string
      dropoffCity?: string
      dropoffPostalCode?: string
      dropoffCountryCode?: string
    }) => pinDropoff(deliveryId as string, payload),
    onSuccess: () => {
      onPinned()
      onClose()
    },
  })

  const [lat, setLat] = useState<number | ''>('')
  const [lng, setLng] = useState<number | ''>('')
  const [address, setAddress] = useState('')
  const [city, setCity] = useState('')
  const [postalCode, setPostalCode] = useState('')

  useEffect(() => {
    if (!detailQuery.data) return
    setLat(detailQuery.data.dropoffLat ?? '')
    setLng(detailQuery.data.dropoffLng ?? '')
    setAddress(detailQuery.data.dropoffAddress ?? '')
    setCity(detailQuery.data.dropoffCity ?? '')
    setPostalCode(detailQuery.data.dropoffPostalCode ?? '')
  }, [detailQuery.data])

  useEffect(() => {
    if (!geocodeMutation.data?.found) return
    if (typeof geocodeMutation.data.lat === 'number') setLat(geocodeMutation.data.lat)
    if (typeof geocodeMutation.data.lng === 'number') setLng(geocodeMutation.data.lng)
    if (geocodeMutation.data.displayName) setAddress(geocodeMutation.data.displayName)
    if (geocodeMutation.data.city) setCity(geocodeMutation.data.city)
    if (geocodeMutation.data.postalCode) setPostalCode(geocodeMutation.data.postalCode)
  }, [geocodeMutation.data])

  useEffect(() => {
    if (!reverseMutation.data?.found) return
    if (reverseMutation.data.city) setCity(reverseMutation.data.city)
    if (reverseMutation.data.postalCode) setPostalCode(reverseMutation.data.postalCode)
    if (reverseMutation.data.displayName) setAddress(reverseMutation.data.displayName)
  }, [reverseMutation.data])

  if (!isOpen) return null

  const markerPosition: LatLngExpression | null =
    typeof lat === 'number' && typeof lng === 'number' ? [lat, lng] : null

  const center: LatLngExpression = markerPosition || [34.7406, 10.7603]

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
      <div className="w-full max-w-3xl rounded-lg border border-[var(--border-default)] bg-[var(--bg-surface)] p-4 shadow-[var(--shadow-md)]">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-base font-semibold">Pin and Geocode Delivery</h3>
          <button type="button" onClick={onClose} className="rounded-md border border-[var(--border-default)] px-3 py-1 text-sm">
            Close
          </button>
        </div>

        {detailQuery.isLoading && <p className="mb-3 text-sm text-[var(--text-secondary)]">Loading delivery details...</p>}

        <div className="grid gap-4 lg:grid-cols-[58%_42%]">
          <div className="h-[320px] overflow-hidden rounded-md border border-[var(--border-default)]">
            <MapContainer center={center} zoom={12} style={{ height: '100%', width: '100%' }}>
              <TileLayer
                attribution='&copy; OpenStreetMap contributors'
                url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
              />
              <ClickListener
                onClick={(newLat, newLng) => {
                  setLat(newLat)
                  setLng(newLng)
                  reverseMutation.mutate({ lat: newLat, lng: newLng })
                }}
              />
              <FlyToMarker position={markerPosition} />
              {markerPosition && (
                <CircleMarker center={markerPosition} pathOptions={{ color: '#0f766e', fillColor: '#0f766e' }} radius={8} />
              )}
            </MapContainer>
          </div>

          <div className="space-y-3">
            <button
              type="button"
              onClick={() => geocodeMutation.mutate()}
              className="h-9 rounded-md border border-[var(--border-default)] px-3 text-sm hover:bg-[var(--bg-surface-alt)]"
            >
              Auto Geocode
            </button>

            <div className="grid grid-cols-2 gap-2">
              <input
                value={lat}
                onChange={(e) => setLat(e.target.value === '' ? '' : Number(e.target.value))}
                placeholder="Latitude"
                className="h-9 rounded-md border border-[var(--border-default)] px-3 text-sm"
              />
              <input
                value={lng}
                onChange={(e) => setLng(e.target.value === '' ? '' : Number(e.target.value))}
                placeholder="Longitude"
                className="h-9 rounded-md border border-[var(--border-default)] px-3 text-sm"
              />
            </div>

            <input
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              placeholder="Address"
              className="h-9 w-full rounded-md border border-[var(--border-default)] px-3 text-sm"
            />
            <div className="grid grid-cols-2 gap-2">
              <input
                value={city}
                onChange={(e) => setCity(e.target.value)}
                placeholder="City"
                className="h-9 rounded-md border border-[var(--border-default)] px-3 text-sm"
              />
              <input
                value={postalCode}
                onChange={(e) => setPostalCode(e.target.value)}
                placeholder="Postal code"
                className="h-9 rounded-md border border-[var(--border-default)] px-3 text-sm"
              />
            </div>

            <p className="text-xs text-[var(--text-secondary)]">
              Zone will be auto-assigned by postal code, with city fallback, when you save this pin.
            </p>

            <button
              type="button"
              disabled={typeof lat !== 'number' || typeof lng !== 'number' || pinMutation.isPending}
              onClick={() => {
                if (typeof lat !== 'number' || typeof lng !== 'number') return
                pinMutation.mutate({
                  lat,
                  lng,
                  dropoffAddress: address || undefined,
                  dropoffCity: city || undefined,
                  dropoffPostalCode: postalCode || undefined,
                  dropoffCountryCode: 'TN',
                })
              }}
              className="h-9 rounded-md bg-[var(--action)] px-4 text-sm font-medium text-white hover:bg-[var(--action-hover)] disabled:opacity-60"
            >
              {pinMutation.isPending ? 'Saving pin...' : 'Save Pin'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

