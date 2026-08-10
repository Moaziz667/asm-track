/**
 * "The exception list just moved" — announced to whoever is displaying a count of it.
 *
 * <p>The sidebar badge polls every thirty seconds on its own timer. That is fine for a figure that
 * drifts with the day, and wrong the moment a dispatcher acts: he marks a delivery handled, the row
 * leaves the desk in front of him, and the number beside the menu keeps claiming the old total for
 * up to half a minute. Two figures on one screen disagreeing about what just happened, with the user
 * having caused it himself.
 *
 * <p>A window event rather than shared query state: the badge is not built on react-query and lives
 * outside the dispatch desk's provider, so there is no cache to invalidate and no context to reach
 * across. One event, fired where the desk already refetches, covers every action that changes the
 * count — acknowledge, cancel, reassign, replan — instead of each one remembering to say so.
 */
export const OPS_TELEMETRY_EVENT = 'asm:ops-telemetry-changed';

/** Fired by the dispatch desk once it has refetched, so the count announced is the settled one. */
export const announceOpsChanged = () => {
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(OPS_TELEMETRY_EVENT));
};
