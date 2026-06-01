
interface LoadingBarProps {
  active: boolean;
}

export function LoadingBar({ active }: LoadingBarProps) {
  if (!active) return null;

  return (
    <>
      <style>{`
        @keyframes asm-loading-bar {
          0%   { transform: translateX(-100%); }
          60%  { transform: translateX(30%); }
          100% { transform: translateX(100%); }
        }
      `}</style>
      <div style={{
        position: 'fixed',
        top: 96,
        left: 0,
        right: 0,
        height: 2,
        background: 'var(--border)',
        zIndex: 2000,
        overflow: 'hidden',
      }}>
        <div style={{
          height: '100%',
          width: '45%',
          background: 'var(--brand)',
          animation: 'asm-loading-bar 1.2s ease-in-out infinite',
          borderRadius: 2,
        }} />
      </div>
    </>
  );
}

export function Spinner({ size = 14, color }: { size?: number; color?: string }) {
  return (
    <svg
      className="animate-spin"
      style={{ width: size, height: size, color: color ?? 'currentColor', flexShrink: 0 }}
      viewBox="0 0 24 24"
      fill="none"
    >
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
    </svg>
  );
}

