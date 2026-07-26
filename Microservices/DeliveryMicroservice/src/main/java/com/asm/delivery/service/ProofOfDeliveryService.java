package com.asm.delivery.service;

import com.asm.delivery.dto.request.ProofOfDeliveryRequest;
import com.asm.delivery.dto.response.ProofOfDeliveryResponse;
import com.asm.delivery.entity.ProofOfDelivery;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.ProofOfDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProofOfDeliveryService {
    private final ProofOfDeliveryRepository podRepo;
    private final com.asm.delivery.storage.MediaUrlResolver mediaUrlResolver;

    @Transactional(readOnly = true)
    public ProofOfDeliveryResponse getPod(UUID deliveryId, String role) {
        ProofOfDelivery pod = podRepo.findByDeliveryId(deliveryId)
                .orElseThrow(() -> AppException.notFound("Proof of delivery not found"));
        if ("CLIENT".equals(role)) {
            return ProofOfDeliveryResponse.builder()
                    .id(pod.getId())
                    .deliveryId(pod.getDeliveryId())
                    .comment(pod.getComment())
                    .collectedAt(pod.getCollectedAt())
                    .lat(pod.getLat())
                    .lng(pod.getLng())
                    .build();
        }
        return toResponse(pod);
    }

    @Transactional(readOnly = true)
    public Optional<ProofOfDeliveryResponse> findPodAdmin(UUID deliveryId) {
        return podRepo.findByDeliveryId(deliveryId).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProofOfDeliveryResponse getPodAdmin(UUID deliveryId) {
        return findPodAdmin(deliveryId)
                .orElseThrow(() -> AppException.notFound("Proof of delivery not found"));
    }

    private ProofOfDeliveryResponse toResponse(ProofOfDelivery pod) {
        return ProofOfDeliveryResponse.builder()
                .id(pod.getId())
                .deliveryId(pod.getDeliveryId())
                .photoBase64(null)
                .signatureBase64(null)
                .signatureUrl(mediaUrlResolver.toPublicUrl(
                        pod.getBonLivraisonPhotoUrl() != null ? pod.getBonLivraisonPhotoUrl() : pod.getSignatureUrl()))
                .photoUrl(mediaUrlResolver.toPublicUrl(pod.getPhotoUrl()))
                .comment(pod.getComment())
                .collectedAt(pod.getCollectedAt())
                .lat(pod.getLat())
                .lng(pod.getLng())
                .build();
    }
}
