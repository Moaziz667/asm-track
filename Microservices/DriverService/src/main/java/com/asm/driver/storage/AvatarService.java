package com.asm.driver.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

/**
 * Driver avatar pipeline + storage. Every upload is re-encoded to JPEG (which strips EXIF — the photo's
 * GPS/metadata — and neutralizes embedded payloads), centre-cropped to a square, and written in two sizes
 * to the public-read {@code driver-avatars} bucket so an {@code <img>} tag can load it without an auth header
 * (same pattern as company logos / POD photos). Returns the public URL of the stored thumbnail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvatarService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final PublicOriginResolver originResolver;

    private static final String BUCKET = "driver-avatars";
    private static final int FULL_PX = 512;
    private static final int THUMB_PX = 96;
    private static final long MAX_BYTES = 8L * 1024 * 1024; // 8 MB raw upload cap

    @PostConstruct
    public void init() {
        ensureBucket();
    }

    /**
     * Validates + processes the raw bytes and stores {version}/full.jpg and {version}/thumb.jpg.
     * Deletes the previous version (best-effort). Returns the public URL of the thumbnail.
     */
    public String store(UUID driverId, byte[] data, int version) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("EMPTY_IMAGE");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("IMAGE_TOO_LARGE");
        if (!isJpegOrPng(data)) throw new IllegalArgumentException("UNSUPPORTED_IMAGE_TYPE");

        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(data));
        } catch (Exception e) {
            throw new IllegalArgumentException("INVALID_IMAGE");
        }
        if (src == null) throw new IllegalArgumentException("UNREADABLE_IMAGE");

        String base = driverId + "/" + version + "/";
        put(base + "full.jpg", toSquareJpeg(src, FULL_PX));
        put(base + "thumb.jpg", toSquareJpeg(src, THUMB_PX));

        if (version > 1) deleteVersion(driverId, version - 1);
        return publicUrl(base + "thumb.jpg");
    }

    public String publicUrlFor(UUID driverId, int version, String size) {
        return publicUrl(driverId + "/" + version + "/" + size + ".jpg");
    }

    // ── Image processing ────────────────────────────────────────────────────────

    /** Centre-crops to a square and scales to {@code size}px, re-encoded as JPEG (EXIF stripped). */
    private byte[] toSquareJpeg(BufferedImage src, int size) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int sx = (w - side) / 2;
        int sy = (h - side) / 2;

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, size, size, sx, sy, sx + side, sy + side, null);
        g.dispose();

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(out, "jpg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("AVATAR_ENCODE_FAILED", e);
        }
    }

    private static boolean isJpegOrPng(byte[] d) {
        if (d.length < 8) return false;
        boolean jpeg = (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
        boolean png = (d[0] & 0xFF) == 0x89 && (d[1] & 0xFF) == 0x50
                && (d[2] & 0xFF) == 0x4E && (d[3] & 0xFF) == 0x47;
        return jpeg || png;
    }

    // ── MinIO plumbing (mirrors MinioStorageService) ─────────────────────────────

    private void put(String path, byte[] bytes) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(path)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("image/jpeg")
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("AVATAR_UPLOAD_FAILED:" + path, e);
        }
    }

    private void deleteVersion(UUID driverId, int version) {
        for (String size : new String[]{"full", "thumb"}) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(BUCKET)
                        .object(driverId + "/" + version + "/" + size + ".jpg")
                        .build());
            } catch (Exception e) {
                log.warn("Failed to delete old avatar v{} for {}: {}", version, driverId, e.getMessage());
            }
        }
    }

    /**
     * Built on the caller's own origin, not on a host fixed at boot.
     *
     * <p>The previous version read MINIO_PUBLIC_URL, so every caller was handed the one address the
     * server was configured with. A driver whose phone reached the API over a hotspot got avatar URLs
     * pointing at the office Wi-Fi — the API answered, the image did not load, and the only fix was
     * an environment variable and a restart.
     */
    private String publicUrl(String objectPath) {
        return originResolver.origin() + PublicOriginResolver.FILES_PREFIX
                + "/" + BUCKET + "/" + objectPath;
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
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
                        """.formatted(BUCKET);
                minioClient.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(BUCKET).config(policy).build());
                log.info("MinIO bucket created: {}", BUCKET);
            }
        } catch (Exception e) {
            log.error("Failed to initialize MinIO bucket {}: {}", BUCKET, e.getMessage());
        }
    }
}
