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

    @PostConstruct
    public void init() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(minioConfig.getBucket()).build());
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(minioConfig.getBucket()).build());

                // Set public read policy
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
                        """.formatted(minioConfig.getBucket());

                minioClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder()
                                .bucket(minioConfig.getBucket())
                                .config(policy)
                                .build());

                log.info("MinIO storage ready — bucket created: {}", minioConfig.getBucket());
            } else {
                log.info("MinIO storage ready — bucket: {}", minioConfig.getBucket());
            }
        } catch (Exception e) {
            log.error("Failed to initialize MinIO bucket: {}", e.getMessage(), e);
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
