import { api } from '@/lib/api'
import type { AdminDeliveryDetail, GeocodeSuggestion, PinDropoffPayload } from '../types/delivery'

export async function getAdminDeliveryDetail(deliveryId: string): Promise<AdminDeliveryDetail> {
  const { data } = await api.get<AdminDeliveryDetail>(`/admin/deliveries/${deliveryId}`)
  return data
}

export async function geocodeDelivery(deliveryId: string): Promise<GeocodeSuggestion> {
  const { data } = await api.get<GeocodeSuggestion>(`/admin/deliveries/${deliveryId}/geocode`)
  return data
}

export async function reverseGeocode(lat: number, lng: number): Promise<GeocodeSuggestion> {
  const { data } = await api.get<GeocodeSuggestion>('/admin/deliveries/reverse-geocode', {
    params: { lat, lng },
  })
  return data
}

export async function pinDropoff(deliveryId: string, payload: PinDropoffPayload): Promise<AdminDeliveryDetail> {
  const { data } = await api.post<AdminDeliveryDetail>(`/admin/deliveries/${deliveryId}/pin-dropoff`, payload)
  return data
}
