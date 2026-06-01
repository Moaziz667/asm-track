export type AdminDeliveryDetail = {
  deliveryId: string
  orderId?: string
  status: string
  clientName: string
  dropoffAddress?: string
  dropoffCity?: string
  dropoffPostalCode?: string
  dropoffCountryCode?: string
  dropoffLat?: number
  dropoffLng?: number
  dropoffPinned: boolean
  zoneId?: string
  zoneName?: string
}

export type GeocodeSuggestion = {
  lat?: number
  lng?: number
  displayName?: string
  city?: string
  postalCode?: string
  found: boolean
  outsideTunisiaBbox?: boolean
}

export type PinDropoffPayload = {
  lat: number
  lng: number
  dropoffAddress?: string
  dropoffCity?: string
  dropoffPostalCode?: string
  dropoffCountryCode?: string
}
