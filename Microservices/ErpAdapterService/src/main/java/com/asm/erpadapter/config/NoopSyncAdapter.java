package com.asm.erpadapter.config;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.port.ErpSyncPort;

import java.util.List;

/** No-op sync adapter for companies with erp_type = NONE. */
class NoopSyncAdapter implements ErpSyncPort {
    @Override public boolean syncOrderCancellation(String id, String txId, String pickingRef) { return true; }
    @Override public boolean syncFullDelivery(String id, Integer b, String txId, String pickingRef) { return true; }
    @Override public ErpPartialDeliveryResultDTO syncPartialDelivery(String id, List<ErpPartialItemDTO> i, String txId, String pickingRef) {
        return new ErpPartialDeliveryResultDTO(true, null, null);
    }
    @Override public boolean syncFailure(String id, String code, String comment, String txId, String pickingRef) { return true; }
}
