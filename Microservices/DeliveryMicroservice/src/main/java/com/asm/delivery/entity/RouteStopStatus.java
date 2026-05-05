package com.asm.delivery.entity;

public enum RouteStopStatus {
    PENDING,
    SCHEDULED,
    PICKED_UP,
    IN_TRANSIT,
    ARRIVED,
    COMPLETED,
    FAILED,
    PARTIAL,
    FAILED_ATTEMPT,      // driver attempted but could not complete (physical visit occurred)
    REMOVED_REPLANNED,   // stop removed, delivery returned to dispatch pool for re-planning
    REMOVED_CANCELLED    // stop removed because the route or delivery was explicitly cancelled
}
