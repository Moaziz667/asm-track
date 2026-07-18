package com.asm.delivery.service;

import com.asm.delivery.dto.request.CreateRmaRequest;
import com.asm.delivery.dto.request.PublicReturnRequest;
import com.asm.delivery.dto.response.PublicReturnableItemsResponse;
import com.asm.delivery.dto.response.RmaResponse;
import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.entity.Rma;
import com.asm.delivery.entity.RmaPhoto;
import com.asm.delivery.entity.RmaStatus;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RmaPhotoRepository;
import com.asm.delivery.repository.RmaRepository;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.storage.RmaPhotoStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public (unauthenticated) return flow used by the client on the tracking page. All RMA business rules
 * — returnable clamp, "delivered/partial only", one-open-return idempotency, transition guards — live in
 * {@link RmaService}; this service only adapts the public shape and attributes actions to a CLIENT actor
 * (never SYSTEM) so the audit trail stays honest.
 */
@Service
@RequiredArgsConstructor
public class PublicRmaService {

    /** Synthetic actor for public self-service actions → audit rows read "CLIENT", not "SYSTEM". */
    private static final UserPrincipal CLIENT_ACTOR =
            new UserPrincipal("public", "CLIENT", "Client (suivi public)", null, null);

    private final DeliveryRepository deliveryRepository;
    private final RmaRepository rmaRepository;
    private final RmaPhotoRepository rmaPhotoRepository;
    private final RmaService rmaService;
    private final RmaPhotoStorageService photoService;
    private final PublicTrackingService publicTrackingService;

    @Transactional(readOnly = true)
    public PublicReturnableItemsResponse getReturnableItems(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("DELIVERY_NOT_FOUND", "Livraison introuvable."));
        Order order = delivery.getOrder();

        Optional<Rma> open = openReturn(deliveryId);
        Map<String, Integer> returnable = rmaService.returnableQuantitiesBySku(delivery);
        Map<String, Integer> delivered = deliveredBySku(order);

        List<PublicReturnableItemsResponse.ReturnableItem> items = new ArrayList<>();
        if (order != null && order.getItems() != null) {
            // One row per distinct SKU, keeping the order-line name/price; only rows with something still
            // returnable are surfaced to the form.
            Map<String, OrderItem> firstBySku = new LinkedHashMap<>();
            for (OrderItem it : order.getItems()) {
                if (it == null || it.getSku() == null || it.getSku().isBlank()) continue;
                firstBySku.putIfAbsent(it.getSku().trim(), it);
            }
            firstBySku.forEach((sku, it) -> {
                int canReturn = returnable.getOrDefault(sku, 0);
                if (canReturn <= 0) return;
                items.add(PublicReturnableItemsResponse.ReturnableItem.builder()
                        .sku(sku)
                        .name(it.getName())
                        .deliveredQty(delivered.getOrDefault(sku, 0))
                        .returnableQty(canReturn)
                        .unitPrice(it.getUnitPrice())
                        .build());
            });
        }

        boolean returnableDelivery = delivery.getStatus() == DeliveryStatus.DELIVERED
                || delivery.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED;

        return PublicReturnableItemsResponse.builder()
                .deliveryStatus(delivery.getStatus() != null ? delivery.getStatus().name() : "UNKNOWN")
                .returnable(returnableDelivery)
                .hasOpenReturn(open.isPresent())
                .openReturnId(open.map(Rma::getId).orElse(null))
                .openReturnStatus(open.map(Rma::getStatus).map(Enum::name).orElse(null))
                .items(items)
                .build();
    }

    @Transactional
    public TrackingResponse createReturn(UUID deliveryId, PublicReturnRequest request) {
        CreateRmaRequest req = new CreateRmaRequest();
        req.setDeliveryId(deliveryId);
        req.setItems(request.getItems());

        // All validation (returnable, delivered/partial, one-open-return) is enforced by create().
        RmaResponse created = rmaService.create(req, CLIENT_ACTOR);

        List<String> urls = request.getPhotoUrls();
        if (urls != null) {
            urls.stream()
                    .filter(u -> u != null && !u.isBlank())
                    .forEach(u -> rmaPhotoRepository.save(
                            RmaPhoto.builder().rmaId(created.getId()).url(u.trim()).build()));
        }
        return publicTrackingService.getTracking(deliveryId);
    }

    @Transactional
    public TrackingResponse cancelReturn(UUID deliveryId) {
        Rma rma = openReturn(deliveryId)
                .orElseThrow(() -> AppException.notFound("RMA_NOT_FOUND", "Aucun retour en cours pour cette livraison."));
        if (rma.getStatus() != RmaStatus.REQUESTED) {
            throw AppException.badRequest("RMA_NOT_CANCELLABLE",
                    "Ce retour ne peut plus être annulé (statut " + rma.getStatus() + ").");
        }
        // Note is mandatory for CANCELLED (RmaService guard) → supplied. CLIENT actor keeps the trail honest.
        rmaService.transition(rma.getId(), RmaStatus.CANCELLED, "Annulé par le client", CLIENT_ACTOR);
        return publicTrackingService.getTracking(deliveryId);
    }

    public List<String> uploadPhotos(UUID deliveryId, List<MultipartFile> files) {
        // Guard against uploading for an unknown delivery (avoids orphan objects in MinIO).
        if (!deliveryRepository.existsById(deliveryId)) {
            throw AppException.notFound("DELIVERY_NOT_FOUND", "Livraison introuvable.");
        }
        return photoService.uploadPhotos(deliveryId, files);
    }

    private Optional<Rma> openReturn(UUID deliveryId) {
        return rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId).stream()
                .filter(r -> RmaService.OPEN_STATUSES.contains(r.getStatus()))
                .findFirst();
    }

    private Map<String, Integer> deliveredBySku(Order order) {
        Map<String, Integer> delivered = new LinkedHashMap<>();
        if (order == null || order.getItems() == null) return delivered;
        for (OrderItem it : order.getItems()) {
            if (it == null || it.getSku() == null || it.getSku().isBlank()) continue;
            int done = it.getQuantityDone() != null ? Math.max(it.getQuantityDone(), 0) : 0;
            delivered.merge(it.getSku().trim(), done, Integer::sum);
        }
        return delivered;
    }
}
