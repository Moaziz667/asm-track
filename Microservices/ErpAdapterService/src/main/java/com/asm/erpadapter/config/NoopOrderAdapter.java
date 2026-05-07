package com.asm.erpadapter.config;

import com.asm.erpadapter.port.ErpOrderPort;

/** No-op order adapter for companies with erp_type = NONE. */
class NoopOrderAdapter implements ErpOrderPort {
    @Override public String resolveOrderId(String ref) { return null; }
    @Override public String getOrderReference(String id) { return null; }
}
