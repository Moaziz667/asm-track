/**
 * Shopify order shape as returned by QueryShopify (GET /orders).
 * MappingShopify uses these as input to mapping.service.ts.
 */
export interface ShopifyAddress {
  address1:     string | null;
  address2:     string | null;
  city:         string | null;
  country_code: string | null;
  first_name:   string | null;
  last_name:    string | null;
  name:         string | null;
  phone:        string | null;
  zip:          string | null;
}

export interface ShopifyCustomer {
  id:         number;
  email:      string | null;
  first_name: string | null;
  last_name:  string | null;
  phone:      string | null;
}

export interface ShopifyLineItem {
  id:                   number;
  title:                string;
  sku:                  string | null;
  quantity:             number;
  fulfillable_quantity: number;
  grams:                number;
  price:                string;
  requires_shipping:    boolean;
  fulfillment_status:   string | null;
}

export interface ShopifyOrder {
  id:                   number;
  name:                 string;           // "#1001"
  created_at:           string;
  updated_at:           string;           // used as writeDate for incremental sync
  processed_at:         string | null;
  cancelled_at:         string | null;
  fulfillment_status:   string | null;    // null | 'partial' | 'fulfilled'
  financial_status:     string;
  currency:             string;
  current_total_price:  string;
  note:                 string | null;
  tags:                 string;           // comma-separated e.g. "vip, loyal"
  payment_gateway_names: string[];
  customer:             ShopifyCustomer | null;
  shipping_address:     ShopifyAddress  | null;
  line_items:           ShopifyLineItem[];
}

export interface ShopifyOrdersApiResponse {
  count:    number;
  syncedAt: string;
  data:     ShopifyOrder[];
}
