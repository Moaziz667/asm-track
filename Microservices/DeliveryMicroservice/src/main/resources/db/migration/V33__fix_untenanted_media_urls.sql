-- Repair media URLs that were persisted WITHOUT the tenant prefix.
--
-- Root cause (fixed in code by MinioStorageService.objectKey being the single source of truth):
-- uploadFile() prefixed the object key with the companyId, but the public getPublicUrl(path) did
-- not. Call sites that built the URL BEFORE uploading — the driver app's POD flow in
-- DriverDeliveryService — therefore stored a URL pointing at a key that was never written, so
-- every POD photo taken from the mobile app 404'd (and, after the tenant-isolation guard landed,
-- also blocked the POD→Odoo sync with "MinIO presign REFUSED").
--
-- The OBJECTS are stored correctly under "{companyId}/pod/..." — only the pointers are wrong.
-- This migration inserts the missing "{companyId}/" segment into those pointers.
--
-- Runs once per tenant schema (Flyway is replayed per schema by TenantSchemaProvisioner /
-- TenantMigrationRunner). Idempotent: rows that already carry the tenant segment are skipped,
-- so re-running is a no-op. Non-company schemas (e.g. public) are skipped entirely.
DO $$
DECLARE
    hex text;
    cid text;
    fixed_pod  int := 0;
    fixed_logo int := 0;
BEGIN
    hex := replace(current_schema(), 'company_', '');
    IF current_schema() NOT LIKE 'company\_%' OR length(hex) <> 32 THEN
        RAISE NOTICE 'V33: schema % is not a tenant schema — skipping', current_schema();
        RETURN;
    END IF;

    -- company_<32hex> → canonical dashed UUID (inverse of TenantSchema.schemaFor)
    cid := substr(hex, 1, 8) || '-' || substr(hex, 9, 4) || '-' || substr(hex, 13, 4) || '-'
        || substr(hex, 17, 4) || '-' || substr(hex, 21, 12);

    -- Proof-of-delivery photos: ".../{bucket}/pod/..." → ".../{bucket}/{cid}/pod/..."
    -- Bucket-name agnostic: we anchor on the "/pod/" logical segment, and the NOT LIKE guard makes
    -- it idempotent (never double-prefixes an already-correct URL).
    UPDATE proof_of_delivery
       SET bon_livraison_photo_url = replace(bon_livraison_photo_url, '/pod/', '/' || cid || '/pod/')
     WHERE bon_livraison_photo_url LIKE '%/pod/%'
       AND bon_livraison_photo_url NOT LIKE '%/' || cid || '/pod/%';
    GET DIAGNOSTICS fixed_pod = ROW_COUNT;

    UPDATE proof_of_delivery
       SET photo_url = replace(photo_url, '/pod/', '/' || cid || '/pod/')
     WHERE photo_url LIKE '%/pod/%'
       AND photo_url NOT LIKE '%/' || cid || '/pod/%';

    UPDATE proof_of_delivery
       SET signature_url = replace(signature_url, '/pod/', '/' || cid || '/pod/')
     WHERE signature_url LIKE '%/pod/%'
       AND signature_url NOT LIKE '%/' || cid || '/pod/%';

    -- Company branding assets, same divergence via CompanyService.uploadLogo.
    UPDATE companies
       SET logo_url = replace(logo_url, '/logos/', '/' || cid || '/logos/')
     WHERE logo_url LIKE '%/logos/%'
       AND logo_url NOT LIKE '%/' || cid || '/logos/%';
    GET DIAGNOSTICS fixed_logo = ROW_COUNT;

    UPDATE companies
       SET logo_url = replace(logo_url, '/company-logos/', '/' || cid || '/company-logos/')
     WHERE logo_url LIKE '%/company-logos/%'
       AND logo_url NOT LIKE '%/' || cid || '/company-logos/%';

    RAISE NOTICE 'V33: tenant % — repaired % POD photo URL(s), % logo URL(s)', cid, fixed_pod, fixed_logo;
END $$;
