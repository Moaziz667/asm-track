package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.erp.client.ErpAdapterClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BonLivraisonPdfService {

    private final DeliveryRepository deliveryRepository;
    private final ErpAdapterClient erpAdapterClient;

    public byte[] generate(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found: " + deliveryId));

        Order order = delivery.getOrder();
        if (order == null || order.getBlNumber() == null || order.getBlNumber().isBlank()) {
            throw AppException.notFound("BL pas encore disponible (non synchronisé)");
        }

        // We use "odoo" provider as default for now, matching sync service patterns
        String provider = "odoo";
        byte[] pdf = erpAdapterClient.getDeliveryNotePdf(order.getBlNumber(), provider);
        
        if (pdf == null) {
            throw AppException.notFound("Impossible de récupérer le PDF du BL depuis l'ERP (BL: " + order.getBlNumber() + ")");
        }

        return pdf;
    }
}
