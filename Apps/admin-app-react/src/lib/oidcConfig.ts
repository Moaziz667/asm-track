import { AuthProviderProps } from 'react-oidc-context';
import { safeStorage } from './storage';
import { jwtDecode } from 'jwt-decode';

export const oidcConfig: AuthProviderProps = {
  authority: 'http://localhost:8089/realms/asm',
  client_id: 'admin-web',
  redirect_uri: window.location.origin + '/callback',
  response_type: 'code',
  scope: 'openid profile email',
  onSigninCallback: (user) => {
    // Save tokens to storage for legacy code compatibility
    if (user?.access_token) {
      safeStorage.setItem('access_token', user.access_token);
      
      try {
        const decoded: any = jwtDecode(user.access_token);
        
        // Extract realm roles for Admin UI
        let role = 'ADMIN'; // Default fallback
        if (decoded.realm_access?.roles?.includes('ADMIN')) role = 'ADMIN';
        else if (decoded.realm_access?.roles?.includes('DISPATCHER')) role = 'DISPATCHER';
        else if (decoded.realm_access?.roles?.includes('MANAGER')) role = 'MANAGER';

        safeStorage.setItem('admin_name', decoded.name || decoded.preferred_username || decoded.email || 'Admin');
        safeStorage.setItem('role', role);
      } catch (e) {
        console.error('Failed to parse JWT', e);
      }
    }
    // Remove OIDC response from URL
    window.history.replaceState(
      {},
      document.title,
      window.location.pathname
    );
  },
  onRemoveUser: () => {
    safeStorage.removeItem('access_token');
    safeStorage.removeItem('admin_user');
    safeStorage.removeItem('admin_name');
    safeStorage.removeItem('role');
  }
};
