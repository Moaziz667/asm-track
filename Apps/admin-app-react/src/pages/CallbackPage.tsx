import { useNavigate } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';

export default function CallbackPage() {
  const auth = useAuth();
  const navigate = useNavigate();

  // On success, PublicRoute redirects authenticated users to /dashboard.
  // This page only renders while the code exchange is in flight, or on error.
  if (auth.error) {
    return (
      <div style={{ height: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column' }}>
        <h2 style={{ color: 'red' }}>Authentication Error</h2>
        <pre style={{ background: '#f5f5f5', padding: 16, borderRadius: 8, maxWidth: 600, overflow: 'auto' }}>
          {auth.error?.message || 'Unknown error occurred'}
        </pre>
        <button onClick={() => navigate('/login')} style={{ marginTop: 16, padding: '8px 16px', background: 'var(--brand)', borderRadius: 4, border: 'none', cursor: 'pointer' }}>
          Go back to Login
        </button>
      </div>
    );
  }

  return (
    <div style={{ height: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ textAlign: 'center' }}>
        <svg className="animate-spin" style={{ margin: '0 auto 16px', color: 'var(--brand)' }} width="32" height="32" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" strokeOpacity="0.25" />
          <path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
        </svg>
        <p style={{ color: 'var(--text-secondary)', fontWeight: 500 }}>
          {auth.isLoading ? 'Authenticating...' : 'Processing callback...'}
        </p>
      </div>
    </div>
  );
}
