/**
 * Build a link to the Dispatch Desk queue, pre-filtered to a single command.
 * The dispatch desk reads the `search` param and applies it as the queue filter
 * (matchSearch covers orderRef / erpId / clientName / deliveryId). Prefer a
 * human-readable command ref for the search box, falling back to the id.
 */
export function dispatchDeskQueueLink(ref: {
  orderRef?: string | null;
  erpOrderId?: string | null;
  orderId?: string | null;
  deliveryId?: string | null;
}): string {
  const q = ref.orderRef || ref.erpOrderId || ref.orderId || ref.deliveryId || '';
  const params = new URLSearchParams({ tab: 'queue' });
  if (q) params.set('search', String(q));
  return `/dispatch-desk?${params.toString()}`;
}
