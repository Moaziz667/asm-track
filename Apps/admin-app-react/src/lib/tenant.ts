import { jwtDecode } from 'jwt-decode';

/**
 * Extract the tenant (company UUID) from a JWT access token: the flat `org_id` claim first, else the id
 * of the first entry in the Keycloak `organization` membership claim.
 *
 * Kept in its own dependency-light module (only `jwt-decode`) so the tenant-routing logic is unit-testable
 * in a plain Node environment — getting this wrong means the client subscribes to the WRONG tenant's live
 * topic (`/topic/company/{id}/…`), a cross-tenant leak. Returns null when no tenant can be resolved.
 */
export function extractCompanyIdFromToken(token: string | null): string | null {
  if (!token) return null;
  try {
    const decoded = jwtDecode<{ org_id?: string; organization?: Record<string, { id?: string }> }>(token);
    if (decoded.org_id) return decoded.org_id;
    const first = decoded.organization ? Object.values(decoded.organization)[0] : undefined;
    return first?.id ?? null;
  } catch {
    return null;
  }
}
