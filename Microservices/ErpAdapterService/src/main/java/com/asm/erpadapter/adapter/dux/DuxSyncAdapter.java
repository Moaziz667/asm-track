package com.asm.erpadapter.adapter.dux;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.port.ErpSyncPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DUX ERP sync adapter — stub pending DUX API documentation.
 * Registered as provider "dux" by ErpProviderRouter naming convention.
 */
@Component
@Slf4j
public class DuxSyncAdapter implements ErpSyncPort {

    @Override
    public boolean syncOrderCancellation(String erpOrderId, String transactionId, String pickingRef) {
        log.warn("DUX syncOrderCancellation not yet implemented for order {} (TX: {})", erpOrderId, transactionId);
        return false;
    }

    @Override
    public boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId, String transactionId, String pickingRef) {
        log.warn("DUX syncFullDelivery not yet implemented for order {} (TX: {})", erpOrderId, transactionId);
        return false;
    }

    @Override
    public ErpPartialDeliveryResultDTO syncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items, String transactionId, String pickingRef) {
        log.warn("DUX syncPartialDelivery not yet implemented for order {} (TX: {})", erpOrderId, transactionId);
        return new ErpPartialDeliveryResultDTO(false, null, null);
    }

    @Override
    public boolean syncFailure(String erpOrderId, String failureCode, String comment, String transactionId, String pickingRef) {
        log.warn("DUX syncFailure not yet implemented for order {} (TX: {})", erpOrderId, transactionId);
        return false;
    }
}
