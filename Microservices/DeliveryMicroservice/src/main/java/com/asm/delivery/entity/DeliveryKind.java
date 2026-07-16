package com.asm.delivery.entity;

/**
 * Direction of a delivery. A FORWARD delivery moves goods depot → client (the default). A RETURN_PICKUP
 * is its mirror — a reverse leg that collects returned goods client → depot for an approved RMA. Both
 * reuse the same {@link DeliveryStatus} machine; only the pickup/dropoff semantics flip (pickup = client,
 * final drop = the return depot), interpreted by the routing/tracking layers via this discriminator.
 * See ADR-033.
 */
public enum DeliveryKind {
    FORWARD,
    RETURN_PICKUP
}
