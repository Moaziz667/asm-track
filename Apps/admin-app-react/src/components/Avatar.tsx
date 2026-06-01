type Props = {
  name?: string;
  size?: number;
  fontSize?: number;
  className?: string;
};

function initialsFromName(name: string): string {
  const clean = name.trim();
  if (!clean) return 'NA';
  return clean
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('') || 'NA';
}

function colorFromName(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i += 1) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  const hue = Math.abs(hash) % 360;
  return `hsl(${hue}, 65%, 42%)`;
}

export default function Avatar({ name = 'Utilisateur', size = 32, fontSize, className }: Props) {
  const initials = initialsFromName(name);
  const bg = colorFromName(name);

  return (
    <div
      aria-label={name}
      title={name}
      className={className}
      style={{
        width: size,
        height: size,
        borderRadius: '50%',
        background: bg,
        color: '#fff',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontWeight: 700,
        fontSize: fontSize ?? Math.max(11, Math.round(size * 0.36)),
        userSelect: 'none',
      }}
    >
      {initials}
    </div>
  );
}

