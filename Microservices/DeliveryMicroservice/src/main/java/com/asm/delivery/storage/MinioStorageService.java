package com.asm.delivery.storage;

import com.asm.tenant.TenantContext;

import io.minio.*;
import io.minio.errors.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    private static final String COMPANY_LOGOS_BUCKET = "company-logos";

    /**
     * Whether the shared POD bucket gets an anonymous-read policy. <b>Now false by default.</b>
     *
     * <p>It used to default to true: the bucket was anonymously readable and the object key was the
     * only protection — except the key is derived from ids the caller already holds
     * ({@code {companyId}/pod/{deliveryId}/…}), so a URL, guessed or leaked, exposed another tenant's
     * proof of delivery. Every read path now goes through {@code MediaUrlResolver}, which signs the
     * URL it hands out, so the anonymous policy has no remaining purpose.
     *
     * <p>Left configurable only as an escape hatch for an operator who hits an unforeseen read path;
     * turning it back on re-opens the cross-tenant exposure, hence the warning on startup.
     */
    @org.springframework.beans.factory.annotation.Value("${minio.pod-bucket-public-read:false}")
    private boolean podBucketPublicRead;

    @PostConstruct
    public void init() {
        ensureBucket(minioConfig.getBucket(), podBucketPublicRead);
        ensureBucket(COMPANY_LOGOS_BUCKET, true); // branding assets are intentionally public
        if (podBucketPublicRead) {
            log.warn("MinIO POD bucket '{}' is PUBLIC-READ (minio.pod-bucket-public-read=true). "
                    + "All tenants' POD photos are readable by anyone with the URL — migrate read "
                    + "paths to presigned URLs and disable this.", minioConfig.getBucket());
        }
    }

    private void ensureBucket(String bucket, boolean publicRead) {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket created: {}", bucket);
            } else {
                log.info("MinIO bucket ready: {}", bucket);
            }
            // Applied on every start (not only creation) so flipping the flag takes effect on
            // existing deployments without manual mc commands.
            if (publicRead) {
                String policy = """
                        {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Effect": "Allow",
                            "Principal": {"AWS": ["*"]},
                            "Action": ["s3:GetObject"],
                            "Resource": ["arn:aws:s3:::%s/*"]
                          }]
                        }
                        """.formatted(bucket);
                minioClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
            } else {
                minioClient.deleteBucketPolicy(DeleteBucketPolicyArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket {} set to private (anonymous policy removed)", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to initialize MinIO bucket {}: {}", bucket, e.getMessage());
        }
    }

    /**
     * Presigned GET for an object this tenant owns — the tenant-safe way to hand a URL to a
     * downstream consumer (ERP adapter, browser) without a public bucket. Verifies the object
     * belongs to the current tenant before signing.
     */
    public String presignedGetUrl(String url, java.time.Duration ttl) {
        String objectPath = resolveKey(url);
        if (objectPath == null) return url; // not one of ours (already presigned / external) — pass through
        requireCurrentTenantObject(objectPath, "presign");
        try {
            return minioClient.getPresignedObjectUrl(
                    io.minio.GetPresignedObjectUrlArgs.builder()
                            .method(io.minio.http.Method.GET)
                            .bucket(minioConfig.getBucket())
                            .object(objectPath)
                            .expiry((int) ttl.toSeconds())
                            .build());
        } catch (Exception e) {
            log.warn("Failed to presign {}: {} — falling back to canonical URL", objectPath, e.getMessage());
            return url;
        }
    }

    /**
     * The signed query string authorising a GET on {@code key}, without any host or path.
     *
     * <p>Exists so a caller can keep serving media through its own origin — the phone gets
     * {@code http://192.168.1.7/files/...}, the browser gets localhost — while the bucket stays
     * private. The signature covers the object path and the {@code Host} header, and the gateway
     * rewrites Host to MinIO's when it proxies {@code /files/**}, so a URL built this way verifies
     * even though the client never talks to MinIO directly.
     *
     * @return the query string (no leading {@code ?}), or {@code null} when signing failed
     */
    public String presignedQueryForKey(String key, java.time.Duration ttl) {
        if (key == null || key.isBlank()) return null;
        requireCurrentTenantObject(key, "presign");
        try {
            String signed = minioClient.getPresignedObjectUrl(
                    io.minio.GetPresignedObjectUrlArgs.builder()
                            .method(io.minio.http.Method.GET)
                            .bucket(minioConfig.getBucket())
                            .object(key)
                            .expiry((int) ttl.toSeconds())
                            .build());
            int q = signed.indexOf('?');
            return q >= 0 ? signed.substring(q + 1) : null;
        } catch (Exception e) {
            log.warn("Failed to presign {}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Cross-tenant guard: object paths are {companyId}/... — refuse to touch an object whose prefix
     * is another tenant's. Stored URLs travel through DB rows, sync payloads and user input; without
     * this check any code path fed a foreign URL becomes an IDOR primitive.
     */
    private void requireCurrentTenantObject(String objectPath, String action) {
        java.util.UUID companyId = com.asm.tenant.TenantContext.get();
        if (companyId == null) return; // tenant-less system paths (e.g. legacy logos) — nothing to assert
        if (!objectPath.startsWith(companyId + "/")) {
            log.warn("MinIO {} REFUSED — object '{}' does not belong to tenant {}", action, objectPath, companyId);
            throw new StorageException(objectPath,
                    new SecurityException("Object does not belong to the current tenant"));
        }
    }

    /**
     * Stores the bytes and returns the <b>storage key</b> (not a URL) — that key is what callers
     * persist. Absolute URLs are built late, per request, by {@link MediaUrlResolver}, so no row
     * ever freezes a hostname.
     */
    public String uploadFile(byte[] data, String contentType, String path) {
        long start = System.currentTimeMillis();
        String objectKey = objectKey(path);
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(data), data.length, -1)
                            .contentType(contentType)
                            .build());

            log.info("Uploaded {} ({} bytes) in {}ms", objectKey, data.length, System.currentTimeMillis() - start);
            return objectKey;
        } catch (Exception e) {
            throw new StorageException(objectKey, e);
        }
    }

    /**
     * The storage key a given logical path will be written to. Call sites that need to record the
     * reference before the (deferred) upload use this — never a URL.
     */
    public String objectKeyFor(String logicalPath) {
        return objectKey(logicalPath);
    }

    /**
     * THE single source of truth for how a logical path becomes a storage key:
     * {@code "pod/{deliveryId}/photo.png"} → {@code "{companyId}/pod/{deliveryId}/photo.png"}.
     *
     * <p>Every write AND every URL must go through this. They used to diverge — {@code uploadFile}
     * prefixed the tenant while the public {@code getPublicUrl(path)} did not, so callers that built
     * a URL before uploading (POD photos, company logos) persisted a URL pointing at a key that was
     * never written. The objects were stored correctly; the links in the database were not, so every
     * POD photo and logo 404'd. Deriving both from this one method makes that class of bug
     * unrepresentable.
     *
     * <p>Fail-closed: a tenant-scoped key without a TenantContext used to silently land at the bucket
     * root outside every tenant's prefix — now it's an error, so the lost-context bug that caused it
     * surfaces instead of hiding.
     */
    private String objectKey(String logicalPath) {
        java.util.UUID companyId = com.asm.tenant.TenantContext.get();
        if (companyId == null) {
            throw new StorageException(logicalPath,
                    new IllegalStateException("No tenant context for tenant-scoped object key"));
        }
        return companyId + "/" + logicalPath;
    }

    /** Builds the externally reachable URL for an ALREADY-resolved storage key. */
    private String urlForKey(String objectKey) {
        String baseUrl = StringUtils.hasText(minioConfig.getPublicUrl())
                ? minioConfig.getPublicUrl()
                : minioConfig.getUrl();
        return baseUrl + "/" + minioConfig.getBucket() + "/" + objectKey;
    }

    public String uploadBase64(String base64, String path) {
        String contentType = "image/png";
        String data = base64;

        // Strip data URI prefix if present
        if (base64.contains(",")) {
            String header = base64.substring(0, base64.indexOf(","));
            data = base64.substring(base64.indexOf(",") + 1);
            if (header.contains("image/jpeg")) {
                contentType = "image/jpeg";
            } else if (header.contains("image/webp")) {
                contentType = "image/webp";
            }
        }

        byte[] bytes = Base64.getDecoder().decode(data);
        return uploadFile(bytes, contentType, path);
    }

    public void deleteFile(String url) {
        try {
            String objectPath = resolveKey(url);
            if (objectPath != null) {
                requireCurrentTenantObject(objectPath, "delete");
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(minioConfig.getBucket())
                                .object(objectPath)
                                .build());
                log.info("Deleted: {}", objectPath);
            }
        } catch (Exception e) {
            log.warn("Failed to delete file {}: {}", url, e.getMessage());
        }
    }

    /** A key that already carries an owning tenant, i.e. "{uuid}/rest/of/path". */
    private static final java.util.regex.Pattern TENANT_PREFIXED = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/.+");

    /**
     * Accepts a storage key, a legacy absolute URL, or a legacy tenant-less logical path, and
     * returns the storage key.
     *
     * <p>The third case matters: references written before keys carried the tenant survive in places
     * a table migration cannot reach — outbox payloads, in-flight AMQP messages, ERP command bodies.
     * Such a value is a bare logical path ({@code pod/{deliveryId}/photo.png}) and the object it
     * names lives under this tenant's prefix, so we resolve it there.
     *
     * <p>A key that already carries a tenant is never rewritten — if it names a <em>different</em>
     * tenant, {@link #requireCurrentTenantObject} must still reject it. Normalising only un-prefixed
     * paths fixes legacy data without opening a cross-tenant hole.
     */
    private String resolveKey(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return null;
        String key;
        if (keyOrUrl.startsWith("http://") || keyOrUrl.startsWith("https://")) {
            key = extractObjectPath(keyOrUrl);
        } else {
            key = keyOrUrl.startsWith("/") ? keyOrUrl.substring(1) : keyOrUrl;
        }
        if (key == null) return null;
        if (!TENANT_PREFIXED.matcher(key).matches()) {
            java.util.UUID companyId = com.asm.tenant.TenantContext.get();
            if (companyId != null) {
                log.debug("Normalising legacy tenant-less media reference '{}' under tenant {}", key, companyId);
                key = companyId + "/" + key;
            }
        }
        return key;
    }

    public String uploadCompanyLogo(java.util.UUID companyId, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return null;
        String ext = (imageBytes.length >= 4
                && imageBytes[0] == (byte) 0x89 && imageBytes[1] == 0x50
                && imageBytes[2] == 0x4E && imageBytes[3] == 0x47) ? "png" : "jpg";
        String contentType = "png".equals(ext) ? "image/png" : "image/jpeg";
        String path = "logos/company-" + companyId + "." + ext;
        return uploadFile(imageBytes, contentType, path);
    }

    public byte[] getBytes(String url) {
        String objectPath = resolveKey(url);
        if (objectPath == null) return null;
        requireCurrentTenantObject(objectPath, "read");
        try (var stream = minioClient.getObject(
                io.minio.GetObjectArgs.builder()
                        .bucket(minioConfig.getBucket())
                        .object(objectPath)
                        .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            log.warn("Failed to fetch logo from MinIO {}: {}", objectPath, e.getMessage());
            return null;
        }
    }

    private String extractObjectPath(String url) {
        if (url == null) {
            return null;
        }

        String internalPrefix = minioConfig.getUrl() + "/" + minioConfig.getBucket() + "/";
        if (url.startsWith(internalPrefix)) {
            return url.substring(internalPrefix.length());
        }

        if (StringUtils.hasText(minioConfig.getPublicUrl())) {
            String publicPrefix = minioConfig.getPublicUrl() + "/" + minioConfig.getBucket() + "/";
            if (url.startsWith(publicPrefix)) {
                return url.substring(publicPrefix.length());
            }
        }

        return null;
    }
}
