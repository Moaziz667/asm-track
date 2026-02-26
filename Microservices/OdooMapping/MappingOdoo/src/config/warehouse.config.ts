/**
 * Default warehouse address configuration.
 *
 * All fields can be overridden via environment variables without touching
 * any business logic.  Change values in docker-compose.yml (or a .env file)
 * and the mapping layer picks them up automatically on next startup.
 */
export interface WarehouseAddress {
  city:        string;
  postalCode:  string;
  countryCode: string;
  fullAddress: string;
  /** Human-readable warehouse / hub name shown in the canonical origin.name */
  warehouseName: string;
}

export const DEFAULT_WAREHOUSE_ADDRESS: WarehouseAddress = {
  warehouseName: process.env.WAREHOUSE_NAME        ?? 'Main Warehouse',
  fullAddress:   process.env.WAREHOUSE_FULL_ADDRESS ?? '123 Logistics Street, Zone Industrielle',
  city:          process.env.WAREHOUSE_CITY         ?? 'Algiers',
  postalCode:    process.env.WAREHOUSE_POSTAL_CODE  ?? '16000',
  countryCode:   process.env.WAREHOUSE_COUNTRY_CODE ?? 'DZ',
};
