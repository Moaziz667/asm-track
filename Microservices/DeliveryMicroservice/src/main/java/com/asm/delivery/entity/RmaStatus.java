package com.asm.delivery.entity;

/**
 * Lifecycle of a return (RMA):
 * REQUESTED → APPROVED → RECEIVED → RESTOCKED, with REJECTED / CANCELLED as terminal off-ramps.
 */
public enum RmaStatus {
    REQUESTED,
    APPROVED,
    RECEIVED,
    RESTOCKED,
    REJECTED,
    CANCELLED
}
