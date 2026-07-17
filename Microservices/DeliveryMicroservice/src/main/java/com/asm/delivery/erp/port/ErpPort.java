package com.asm.delivery.erp.port;

import java.util.List;
import java.util.Map;

/**
 * The seam between ASM and whatever ERP an instance is wired to, for the <b>synchronous lookup</b>
 * operations (reference data pulled on demand: clients, products, pending orders, warehouses, the
 * company record, picking refs, delivery-note PDFs).
 *
 * <p><b>Scope note — why only lookups:</b> delivery <em>outcomes</em> (full / partial / failure /
 * cancellation / POD / return) are pushed to the ERP <em>asynchronously</em> through the transactional
 * outbox and RabbitMQ ({@code ErpSyncService} → {@code ErpSyncCommandPublisher}), with the result
 * arriving back on {@code erp.sync.result}. That write path already carries a provider key on the
 * command and is its own seam. This port covers the request/response reads that previously went through
 * the {@code ErpAdapterClient} wrapper.
 *
 * <p>One instance serves one ERP, selected at startup by {@code erp.provider}, so there is no per-call
 * provider argument — the wired {@link ErpPort} bean <em>is</em> the provider. A second ERP is one more
 * {@code implements ErpPort} adapter plus a config flip; no caller changes. (This port replaces the
 * former {@code ErpAdapterClient} wrapper, which has been folded into {@code OdooErpAdapter}.)
 *
 * <p><b>Failure contract:</b> every method is defensive — transport/ERP errors are caught by the adapter
 * and surfaced as a safe default (empty list / {@code null}), never thrown.
 */
public interface ErpPort {

    List<Map<String, Object>> searchClients(String search, int limit);

    List<Map<String, Object>> searchProducts(String search, int limit);

    List<Map<String, Object>> getPendingOrders(int limit);

    Map<String, Object> getPendingOrderPreview(String erpOrderId);

    List<Map<String, Object>> getWarehouses();

    String getPickingRef(String pickingId);

    Map<String, Object> getCompany();
}
