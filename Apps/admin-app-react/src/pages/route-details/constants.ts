import { colors } from '@/lib/design-tokens';

const st = colors.status;

export const STOP_STATUS: Record<string, { color: string; bg: string; dot: string; border: string }> = {
  SCHEDULED:           { color: st.SCHEDULED.text,           bg: st.SCHEDULED.bg,           dot: st.SCHEDULED.dot,           border: st.SCHEDULED.border },
  PICKED_UP:           { color: st.PICKED_UP.text,           bg: st.PICKED_UP.bg,           dot: st.PICKED_UP.dot,           border: st.PICKED_UP.border },
  IN_TRANSIT:          { color: st.IN_TRANSIT.text,          bg: st.IN_TRANSIT.bg,          dot: st.IN_TRANSIT.dot,          border: st.IN_TRANSIT.border },
  DELIVERED:           { color: st.DELIVERED.text,           bg: st.DELIVERED.bg,           dot: st.DELIVERED.dot,           border: st.DELIVERED.border },
  COMPLETED:           { color: st.CLOSED.text,              bg: st.CLOSED.bg,              dot: st.CLOSED.dot,              border: st.CLOSED.border },
  FAILED:              { color: st.FAILED.text,              bg: st.FAILED.bg,              dot: st.FAILED.dot,              border: st.FAILED.border },
  PARTIAL:             { color: st.PARTIALLY_DELIVERED.text,  bg: st.PARTIALLY_DELIVERED.bg,  dot: st.PARTIALLY_DELIVERED.dot,  border: st.PARTIALLY_DELIVERED.border },
  PARTIALLY_DELIVERED: { color: st.PARTIALLY_DELIVERED.text,  bg: st.PARTIALLY_DELIVERED.bg,  dot: st.PARTIALLY_DELIVERED.dot,  border: st.PARTIALLY_DELIVERED.border },
  CANCELLED:           { color: st.CANCELLED.text,           bg: st.CANCELLED.bg,           dot: st.CANCELLED.dot,           border: st.CANCELLED.border },
  REMOVED:             { color: st.CANCELLED.text,           bg: st.CANCELLED.bg,           dot: st.CANCELLED.dot,           border: st.CANCELLED.border },
  REMOVED_REPLANNED:   { color: st.REMOVED_REPLANNED.text,   bg: st.REMOVED_REPLANNED.bg,   dot: st.REMOVED_REPLANNED.dot,   border: st.REMOVED_REPLANNED.border },
  REMOVED_CANCELLED:   { color: st.REMOVED_CANCELLED.text,   bg: st.REMOVED_CANCELLED.bg,   dot: st.REMOVED_CANCELLED.dot,   border: st.REMOVED_CANCELLED.border },
  FAILED_ATTEMPT:      { color: st.FAILED_ATTEMPT.text,      bg: st.FAILED_ATTEMPT.bg,      dot: st.FAILED_ATTEMPT.dot,      border: st.FAILED_ATTEMPT.border },
};

export const STATUS_COLORS: Record<string, string> = {
  SCHEDULED: st.SCHEDULED.dot, PICKED_UP: st.PICKED_UP.dot, IN_TRANSIT: st.IN_TRANSIT.dot,
  COMPLETED: st.CLOSED.dot, DELIVERED: st.DELIVERED.dot, FAILED: st.FAILED.dot,
  CANCELLED: st.CANCELLED.dot, PARTIALLY_DELIVERED: st.PARTIALLY_DELIVERED.dot,
  UNSCHEDULED: st.UNSCHEDULED.dot, ARRIVED: st.IN_TRANSIT.dot,
};

export const REMOVABLE_STOP_STATUSES = new Set(['PENDING', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT']);
