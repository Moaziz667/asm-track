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

/**
 * THE single source of truth for where a notification click goes — used by the toast, the bell,
 * and the notifications page so they can never diverge:
 *   • ERP "orders ready" → import (ready tab)
 *   • anything with a delivery/order → dispatch desk, pre-filtered to that command (where actions live)
 *   • a route-only event → that route's detail page (NB: list is /routes-table, not /routes)
 *   • otherwise → the notifications page
 */
export function notifDestination(n: {
  event?: string;
  deliveryId?: string | null;
  orderId?: string | null;
  routeId?: string | null;
  category?: string;
}): string {
  if (n.event === 'erp.orders_ready') return '/import?tab=ready';
  if (n.deliveryId || n.orderId) {
    return dispatchDeskQueueLink({ orderRef: n.orderId, orderId: n.orderId, deliveryId: n.deliveryId });
  }
  if (n.routeId) return `/routes/${n.routeId}`;
  return '/notifications';
}
