/**
 * Default warehouse address configuration.
 * Override via environment variables in docker-compose — no code change needed.
 */
export interface WarehouseAddress {
  city:          string;
  postalCode:    string;
  countryCode:   string;
  fullAddress:   string;
  warehouseName: string;
}

export const DEFAULT_WAREHOUSE_ADDRESS: WarehouseAddress = {
  warehouseName: process.env.WAREHOUSE_NAME        ?? 'Main Warehouse',
  fullAddress:   process.env.WAREHOUSE_FULL_ADDRESS ?? '123 Logistics Street, Zone Industrielle',
  city:          process.env.WAREHOUSE_CITY         ?? 'Algiers',
  postalCode:    process.env.WAREHOUSE_POSTAL_CODE  ?? '16000',
  countryCode:   process.env.WAREHOUSE_COUNTRY_CODE ?? 'DZ',
};
