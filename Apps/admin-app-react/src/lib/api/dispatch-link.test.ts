import { describe, it, expect } from 'vitest';
import { notifDestination } from './dispatch-link';

/**
 * Where a notification click lands. Every surface (toast, bell, notifications page, activity
 * ticker) routes through this one function, so a wrong branch here is wrong in four places at once.
 *
 * The case that motivated these tests: an ERP sync failure carries a deliveryId, which used to send
 * it down the generic branch and onto the dispatch desk — a screen with no control that repairs it.
 */
describe('notifDestination', () => {
  it('sends an ERP sync failure to System Health, despite it carrying a deliveryId', () => {
    expect(notifDestination({ event: 'erp.sync_failed', deliveryId: 'd-1', orderId: 'o-1' }))
      .toBe('/system-health');
  });

  it('still sends other delivery events to the dispatch queue, pre-filtered', () => {
    expect(notifDestination({ event: 'sla.breach', deliveryId: 'd-1', orderId: 'o-1' }))
      .toBe('/dispatch-desk?tab=queue&search=o-1');
  });

  it('opens a completed delivery on its own page — nothing left to dispatch', () => {
    expect(notifDestination({ event: 'delivery.completed', deliveryId: 'd-1' }))
      .toBe('/deliveries/d-1');
  });

  it('keeps the ERP "orders ready" and returns branches intact', () => {
    expect(notifDestination({ event: 'erp.orders_ready' })).toBe('/import?tab=ready');
    expect(notifDestination({ event: 'return.requested', deliveryId: 'd-1', eventParams: { rmaId: 'r-9' } }))
      .toBe('/returns?rma=r-9');
  });

  it('falls back to the notifications page when there is nothing to open', () => {
    expect(notifDestination({ event: 'something.unknown' })).toBe('/notifications');
  });
});
