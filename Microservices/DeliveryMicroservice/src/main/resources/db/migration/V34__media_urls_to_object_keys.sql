-- Normalise persisted media references from absolute URLs to bare MinIO object keys.
--
-- Why: an absolute URL freezes the server's address into the row. The live database held
--   http://10.116.151.125/files/pod-files/<key>   and
--   http://192.168.1.7/files/pod-files/<key>
-- side by side — each photo pinned to whatever IP the host happened to have at upload time, so a
-- new DHCP lease, a different Wi-Fi, or the move to a production domain silently killed every older
-- image. Re-pointing MINIO_PUBLIC_URL only ever fixed rows written afterwards.
--
-- After this migration the row carries only "{companyId}/pod/{deliveryId}/photo.png"; the absolute
-- URL is rebuilt per request by MediaUrlResolver from the origin the caller actually used, so the
-- same row serves a phone on the LAN, a browser on localhost, and production behind HTTPS.
--
-- Idempotent: only rows still holding an http(s) URL are touched, and the key is taken as
-- everything after "/{bucket}/", so it works regardless of which host/proxy path was baked in.
-- MediaUrlResolver also normalises legacy URLs at read time, so this migration is a cleanup rather
-- than a correctness dependency — a row missed here (e.g. a bucket renamed in config) still renders.
DO $$
DECLARE
    bucket   text := 'pod-files';   -- MINIO_BUCKET default; rows for another bucket are left alone
    marker   text;
    n_pod    int  := 0;
    n_logo   int  := 0;
BEGIN
    IF current_schema() NOT LIKE 'company\_%' THEN
        RAISE NOTICE 'V34: schema % is not a tenant schema — skipping', current_schema();
        RETURN;
    END IF;
    marker := '/' || bucket || '/';

    UPDATE proof_of_delivery
       SET bon_livraison_photo_url =
             substring(bon_livraison_photo_url from position(marker in bon_livraison_photo_url) + length(marker))
     WHERE bon_livraison_photo_url LIKE 'http%' AND position(marker in bon_livraison_photo_url) > 0;
    GET DIAGNOSTICS n_pod = ROW_COUNT;

    UPDATE proof_of_delivery
       SET photo_url = substring(photo_url from position(marker in photo_url) + length(marker))
     WHERE photo_url LIKE 'http%' AND position(marker in photo_url) > 0;

    UPDATE proof_of_delivery
       SET signature_url = substring(signature_url from position(marker in signature_url) + length(marker))
     WHERE signature_url LIKE 'http%' AND position(marker in signature_url) > 0;

    UPDATE companies
       SET logo_url = substring(logo_url from position(marker in logo_url) + length(marker))
     WHERE logo_url LIKE 'http%' AND position(marker in logo_url) > 0;
    GET DIAGNOSTICS n_logo = ROW_COUNT;

    RAISE NOTICE 'V34: schema % — % POD reference(s), % logo(s) converted to object keys',
                 current_schema(), n_pod, n_logo;
END $$;
