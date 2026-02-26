/**
 * RAW SHOPIFY ORDER TYPES
 *
 * These interfaces represent ONLY the fields we care about from the
 * Shopify Orders API (GET /admin/api/2024-01/orders.json).
 *
 * Shopify docs: https://shopify.dev/docs/api/admin-rest/2024-01/resources/order
 *
 * This layer MUST NOT contain any mapping logic.
 */

export interface ShopifyAddress {
  address1:     string | null;
  address2:     string | null;
  city:         string | null;
  company:      string | null;
  country:      string | null;
  country_code: string | null;
  first_name:   string | null;
  last_name:    string | null;
  name:         string | null;
  phone:        string | null;
  province:     string | null;
  province_code:string | null;
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
  name:                 string;
  sku:                  string | null;
  quantity:             number;
  fulfillable_quantity: number;
  grams:                number;
  price:                string;
  vendor:               string | null;
  requires_shipping:    boolean;
  fulfillment_status:   string | null;
}

export interface ShopifyOrder {
  id:                 number;
  name:               string;           // e.g. "#1001"
  created_at:         string;
  updated_at:         string;
  processed_at:       string | null;
  cancelled_at:       string | null;
  fulfillment_status: string | null;    // null | 'partial' | 'fulfilled'
  financial_status:   string;           // 'authorized' | 'paid' | 'pending' | ...
  currency:           string;
  current_total_price:string;
  note:               string | null;
  tags:               string;
  payment_gateway_names: string[];
  customer:           ShopifyCustomer | null;
  shipping_address:   ShopifyAddress  | null;
  billing_address:    ShopifyAddress  | null;
  line_items:         ShopifyLineItem[];
}

export interface ShopifyOrdersResponse {
  orders: ShopifyOrder[];
}
