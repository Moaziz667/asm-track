package com.asm.delivery.sla;

/**
 * The single severity axis. Live phases use ON_TRACK/AT_RISK/BREACHED; terminal phases
 * resolve to MET (on time) / LATE; failed/cancelled carry NONE (no lateness to judge).
 */
public enum SlaHealth {
    ON_TRACK,
    AT_RISK,
    BREACHED,
    MET,
    LATE,
    NONE;

    /** True for the two live states that warrant a dispatcher alert. */
    public boolean isAlertable() {
        return this == AT_RISK || this == BREACHED;
    }
}
