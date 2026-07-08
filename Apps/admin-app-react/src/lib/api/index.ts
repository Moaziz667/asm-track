export { TOKEN_KEY, api, registerTokenRefresher, registerLogoutHandler } from './api';
export type { AdminRole } from './auth';
export { getCurrentRole, getCurrentUser, isReadOnlyRole, canManageSettings, canImportErp, canDispatch, canManageRoutes, canManageMasterData, canViewReadOnly, useRoleGuard } from './auth';
export { syncSession, oidcConfig } from './oidcConfig';
export { dispatchDeskQueueLink, notifDestination } from './dispatch-link';
