package com.asm.delivery.entity;

/** Physical condition of a returned item — drives whether it can be restocked as sellable. */
public enum RmaItemCondition {
    RESELLABLE,
    DAMAGED
}
