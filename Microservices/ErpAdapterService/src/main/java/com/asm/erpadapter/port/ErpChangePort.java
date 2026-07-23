package com.asm.erpadapter.port;

import com.asm.erpadapter.dto.ErpOrderChangeDTO;

import java.util.List;

/**
 * Inbound-change port: polls one ERP for orders modified since a cursor. The provider-agnostic
 * counterpart of {@link ErpLookupPort}/{@link ErpSyncPort}/{@link ErpOrderPort} — one implementation
 * per ERP (Odoo, ERPNext, …), selected per tenant by {@code ErpProviderRouter}. This is what lets the
 * inbound reconciliation be genuinely multi-ERP instead of hardcoded to Odoo.
 *
 * <p>Implementations read their connection from the current tenant's settings (via {@code SettingsClient}),
 * so the caller only has to set the {@code TenantContext} before invoking them.
 */
public interface ErpChangePort {

    /**
     * The provider's notion of "now", used to seed a tenant's first cursor so the initial poll doesn't
     * replay the ERP's whole history. Format must be comparable by the same provider's {@link #fetchChanges}.
     */
    String initialCursor();

    /**
     * Orders changed strictly after {@code sinceCursor}, ascending by change stamp, capped at {@code limit}.
     * Never throws — transport errors yield an empty list so the next tick retries (cursor unchanged).
     */
    List<ErpOrderChangeDTO> fetchChanges(String sinceCursor, int limit);
}
