-- Per-phase worst-health history for the SLA timeline. SlaState stores only the live (phase,health);
-- once a delivery advances, a passed phase used to render a flat green "done" even if it was late
-- at the time. This jsonb map records the worst health each lifecycle phase ever reached
-- (e.g. {"DEPARTURE":"BREACHED","DELIVERY":"AT_RISK"}) so the timeline can colour passed phases
-- truthfully instead of always green.
ALTER TABLE sla_state ADD COLUMN IF NOT EXISTS phase_health jsonb;
