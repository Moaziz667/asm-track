
interface AppLoaderProps {
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  centered?: boolean;
  label?: string;
  height?: string | number;
}

const SIZE_MAP = { xs: 12, sm: 16, md: 22, lg: 28, xl: 36 };

export function AppLoader({ size = 'sm', centered = false, label, height }: AppLoaderProps) {
  const px = SIZE_MAP[size];

  const spinner = (
    <svg
      className="animate-spin"
      style={{ width: px, height: px, color: 'var(--brand)', flexShrink: 0 }}
      viewBox="0 0 24 24"
      fill="none"
    >
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
    </svg>
  );

  if (centered || label) {
    return (
      <div
        className="flex items-center justify-center"
        style={{ height: height ?? (centered ? '100%' : 'auto') }}
      >
        <div className="flex flex-col items-center gap-2">
          {spinner}
          {label && (
            <p className="text-xs font-bold uppercase tracking-widest text-[var(--text-muted)]">
              {label}
            </p>
          )}
        </div>
      </div>
    );
  }

  return spinner;
}

