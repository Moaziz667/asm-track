
interface Shortcut {
  key: string;
  description: string;
}

interface KeyboardHintProps {
  shortcuts: Shortcut[];
}

export function KeyboardHint({ shortcuts }: KeyboardHintProps) {
  return (
    <div
      className="flex flex-wrap items-center gap-3 px-3 py-1.5"
      style={{ borderTop: '1px solid var(--border)', background: 'var(--app-bg)' }}
    >
      {shortcuts.map(({ key, description }) => (
        <div key={key} className="flex items-center gap-1.5">
          <kbd
            className="inline-flex items-center justify-center px-1.5 py-0.5 rounded border font-mono text-2xs font-bold"
            style={{
              background: 'var(--surface)',
              borderColor: 'var(--border)',
              color: 'var(--text-primary)',
              fontFamily: "'JetBrains Mono', monospace",
            }}
          >
            {key}
          </kbd>
          <span className="text-2xs text-[var(--text-muted)]">{description}</span>
        </div>
      ))}
    </div>
  );
}

