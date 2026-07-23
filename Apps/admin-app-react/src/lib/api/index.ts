export { TOKEN_KEY, api, registerTokenRefresher, registerLogoutHandler } from './api';
export type { AdminRole } from './auth';
export { getCurrentRole, getCurrentUser, getCurrentPerms, hasPerm, hasAnyPerm, usePermissions } from './auth';
export type { Perm } from './auth';
export { syncSession, oidcConfig } from './oidcConfig';
export { dispatchDeskQueueLink, notifDestination } from './dispatch-link';
