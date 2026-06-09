package com.asm.delivery.sla;

import com.asm.delivery.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Company-tunable SLA thresholds, read live from {@link SystemSettingsService}. Defaults preserve
 * the previous system's values so behaviour doesn't shift on cut-over. All windows are minutes.
 */
@Component
@RequiredArgsConstructor
public class SlaPolicy {

    private final SystemSettingsService settings;

    /** Planning: alert when within this many minutes of the scheduled-day deadline. */
    public int planningLeadMinutes() {
        return settings.getInt("ops.sla.assign-leadtime-minutes", 120);
    }

    /** How long before a phase's dueAt we flip ON_TRACK → AT_RISK. */
    public int atRiskWindowMinutes() {
        return settings.getInt("ops.sla.at-risk-window-minutes", 30);
    }

    /**
     * Grace after a re-plan / stop removal during which the planning alarm is suppressed, so a
     * re-pooled delivery is not instantly re-flagged "as if newly imported".
     */
    public int replanGraceMinutes() {
        return settings.getInt("ops.sla.replan-grace-minutes", 60);
    }
}
