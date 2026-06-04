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

    @PostConstruct
    public void init() {
        ensureBucket(minioConfig.getBucket());
        ensureBucket(COMPANY_LOGOS_BUCKET);
    }

    private void ensureBucket(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
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
                log.info("MinIO bucket created: {}", bucket);
            } else {
                log.info("MinIO bucket ready: {}", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to initialize MinIO bucket {}: {}", bucket, e.getMessage());
        }
    }

    public String uploadFile(byte[] data, String contentType, String path) {
        long start = System.currentTimeMillis();
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(path)
                            .stream(new ByteArrayInputStream(data), data.length, -1)
                            .contentType(contentType)
                            .build());

            String url = getPublicUrl(path);
            log.info("Uploaded {} ({} bytes) in {}ms", path, data.length, System.currentTimeMillis() - start);
            return url;
        } catch (Exception e) {
            throw new StorageException(path, e);
        }
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
            String objectPath = extractObjectPath(url);
            if (objectPath != null) {
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

    public String getPublicUrl(String objectPath) {
        String baseUrl = StringUtils.hasText(minioConfig.getPublicUrl())
                ? minioConfig.getPublicUrl()
                : minioConfig.getUrl();
        return baseUrl + "/" + minioConfig.getBucket() + "/" + objectPath;
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
        String objectPath = extractObjectPath(url);
        if (objectPath == null) return null;
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
