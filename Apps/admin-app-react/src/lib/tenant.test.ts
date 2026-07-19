import { describe, it, expect } from 'vitest';
import { extractCompanyIdFromToken } from './tenant';

/**
 * Locks the tenant-resolution rule the realtime client uses to pick which company topic to subscribe to.
 * A wrong answer here = subscribing to another tenant's live feed, so the precedence and the null cases
 * matter as much as the happy path.
 */

/** Build a fake (unsigned) JWT with the given payload — jwtDecode only base64-decodes, it never verifies. */
function jwt(payload: Record<string, unknown>): string {
  // base64url via btoa (no Node Buffer, so it type-checks under the browser lib): payloads are ASCII JSON.
  const b64url = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64url({ alg: 'none', typ: 'JWT' })}.${b64url(payload)}.sig`;
}

const ORG = '5c72a175-6f18-4b61-8d21-ff7fd15938b3';

describe('extractCompanyIdFromToken', () => {
  it('reads the flat org_id claim', () => {
    expect(extractCompanyIdFromToken(jwt({ org_id: ORG }))).toBe(ORG);
  });

  it('falls back to the first organization membership claim id', () => {
    const token = jwt({ organization: { 'acme-sfax': { id: ORG } } });
    expect(extractCompanyIdFromToken(token)).toBe(ORG);
  });

  it('prefers org_id over the organization claim', () => {
    const token = jwt({ org_id: ORG, organization: { other: { id: 'zzzz' } } });
    expect(extractCompanyIdFromToken(token)).toBe(ORG);
  });

  it('returns null when no tenant claim is present', () => {
    expect(extractCompanyIdFromToken(jwt({ sub: 'user-1' }))).toBeNull();
  });

  it('returns null for a null token', () => {
    expect(extractCompanyIdFromToken(null)).toBeNull();
  });

  it('returns null (never throws) for a malformed token', () => {
    expect(extractCompanyIdFromToken('not-a-jwt')).toBeNull();
  });
});
