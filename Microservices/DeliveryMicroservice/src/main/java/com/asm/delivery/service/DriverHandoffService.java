package com.asm.delivery.service;

import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.dto.response.HandoffTokenResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Handoff;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.HandoffRepository;
import com.asm.delivery.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Custody transfer between two drivers, addressed by delivery id.
 *
 * <p>A thin layer over {@link HandoffService}, which owns the lifecycle, the hardened token, the
 * evidence and the real-time events. What lives here is only the translation from "this delivery" to
 * "the handoff currently open on it" — the shape the driver app asks in, because a driver holds a
 * parcel, not a handoff record.
 *
 * <p>Split out of the former {@code DriverDeliveryService} because it shares nothing with the rest of
 * the journey: no status transition, no history line, no ownership check of its own — the handoff
 * service performs its own, against the token.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DriverHandoffService {

    private final HandoffRepository handoffRepository;
    private final HandoffService handoffService;
    private final DeliveryRepository deliveryRepo;
    private final DriverDeliveryMapper mapper;


    /**
     * Legacy delivery-id-keyed endpoints — thin adapters over {@link HandoffService},
     * which owns the lifecycle, hardened token, evidence and real-time events.
     */
    @Transactional
    public HandoffTokenResponse generateHandoffToken(UUID deliveryId, UUID driverId) {
        Handoff handoff = handoffRepository.findActiveByDeliveryId(deliveryId)
                .orElseThrow(() -> AppException.badRequest("This delivery is not awaiting a handoff"));
        Handoff updated = handoffService.generateToken(handoff.getId(), driverId);
        return HandoffTokenResponse.builder()
                .token(updated.getToken())
                .deliveryId(deliveryId.toString())
                .expiresAt(updated.getTokenExpiresAt())
                .build();
    }

    @Transactional
    public DriverDeliveryResponse confirmHandoff(UUID deliveryId, UUID driverId, String token, UserPrincipal principal) {
        return confirmHandoff(deliveryId, driverId, token, null, null, null, principal);
    }

    @Transactional
    public DriverDeliveryResponse confirmHandoff(UUID deliveryId, UUID driverId, String token,
            java.math.BigDecimal lat, java.math.BigDecimal lng, String notes, UserPrincipal principal) {
        Handoff handoff = handoffRepository.findActiveByDeliveryId(deliveryId).orElse(null);
        if (handoff == null) {
            // No open handoff — already confirmed or never required: return current state idempotently.
            Delivery current = deliveryRepo.findByIdWithOrder(deliveryId)
                    .orElseThrow(() -> AppException.notFound("Delivery not found"));
            return mapper.toDriverDeliveryResponse(current);
        }
        Delivery delivery = handoffService.confirm(handoff.getId(), driverId, token, lat, lng, null, notes);
        return mapper.toDriverDeliveryResponse(delivery);
    }
}
