package com.asm.delivery.storage;

import com.asm.delivery.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stores client-uploaded return photos in MinIO under {@code rma/{deliveryId}/client/{uuid}.{ext}}.
 * Mirrors the company-logo upload path; validates count / size / content-type so the public endpoint
 * can't be used to dump arbitrary blobs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RmaPhotoStorageService {

    private static final int MAX_FILES = 5;
    private static final long MAX_BYTES = 5L * 1024 * 1024; // 5 MB per file
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png");

    private final MinioStorageService minioStorageService;

    /** Validate + upload each photo; returns the public MinIO URLs in input order. */
    public List<String> uploadPhotos(UUID deliveryId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw AppException.badRequest("RMA_PHOTO_EMPTY", "Aucune photo fournie.");
        }
        if (files.size() > MAX_FILES) {
            throw AppException.badRequest("RMA_PHOTO_TOO_MANY", "Maximum " + MAX_FILES + " photos.");
        }

        List<String> urls = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            if (file.getSize() > MAX_BYTES) {
                throw AppException.badRequest("RMA_PHOTO_TOO_LARGE", "Chaque photo doit faire moins de 5 Mo.");
            }
            String contentType = file.getContentType();
            if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
                throw AppException.badRequest("RMA_PHOTO_BAD_TYPE", "Seules les images JPEG ou PNG sont acceptées.");
            }
            String ext = "image/png".equalsIgnoreCase(contentType) ? "png" : "jpg";
            String path = "rma/" + deliveryId + "/client/" + UUID.randomUUID() + "." + ext;
            try {
                urls.add(minioStorageService.uploadFile(file.getBytes(), contentType, path));
            } catch (IOException e) {
                log.warn("RMA photo upload failed for delivery {}: {}", deliveryId, e.getMessage());
                throw AppException.badRequest("RMA_PHOTO_UPLOAD_FAILED", "Échec du téléversement de la photo.");
            }
        }
        if (urls.isEmpty()) {
            throw AppException.badRequest("RMA_PHOTO_EMPTY", "Aucune photo valide fournie.");
        }
        return urls;
    }
}
