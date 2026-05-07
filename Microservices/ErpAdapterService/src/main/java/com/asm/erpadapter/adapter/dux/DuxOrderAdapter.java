package com.asm.erpadapter.adapter.dux;

import com.asm.erpadapter.port.ErpOrderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DUX ERP order adapter — stub pending DUX API documentation.
 */
@Component
@Slf4j
public class DuxOrderAdapter implements ErpOrderPort {

    @Override
    public String resolveOrderId(String erpOrderRef) {
        log.warn("DUX resolveOrderId not yet implemented for ref {}", erpOrderRef);
        return null;
    }

    @Override
    public String getOrderReference(String erpOrderId) {
        log.warn("DUX getOrderReference not yet implemented for id {}", erpOrderId);
        return null;
    }
}
