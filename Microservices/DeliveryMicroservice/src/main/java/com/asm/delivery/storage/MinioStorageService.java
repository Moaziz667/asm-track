package com.asm.delivery.storage;

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
     * Whether the shared POD bucket gets an anonymous-read policy. Historical behavior (and the
     * default, so nothing breaks on upgrade) is public-read with unguessable UUID paths as the only
     * protection — every tenant's POD photos are downloadable by anyone holding a URL. Flip this to
     * false once every read path (admin POD views, PDF reports, public RMA page) uses
     * {@link #presignedGetUrl}; the ERP sync path already does.
     */
    @org.springframework.beans.factory.annotation.Value("${minio.pod-bucket-public-read:true}")
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
     * Cross-tenant guard: object paths are {companyId}/... — refuse to touch an object whose prefix
     * is another tenant's. Stored URLs travel through DB rows, sync payloads and user input; without
     * this check any code path fed a foreign URL becomes an IDOR primitive.
     */
    private void requireCurrentTenantObject(String objectPath, String action) {
        java.util.UUID companyId = com.asm.delivery.security.TenantContext.get();
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
        java.util.UUID companyId = com.asm.delivery.security.TenantContext.get();
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

    /**
     * Accepts either a storage key or a legacy absolute URL and returns the key, so every entry
     * point below works with rows written before media references became keys.
     */
    private String resolveKey(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return null;
        if (!keyOrUrl.startsWith("http://") && !keyOrUrl.startsWith("https://")) {
            return keyOrUrl.startsWith("/") ? keyOrUrl.substring(1) : keyOrUrl;
        }
        return extractObjectPath(keyOrUrl);
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
