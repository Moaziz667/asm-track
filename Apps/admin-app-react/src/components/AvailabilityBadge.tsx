type Props = {
  available?: boolean;
};

export default function AvailabilityBadge({ available = false }: Props) {
  const color = available ? '#10B981' : '#EF4444';
  const bg    = available ? 'rgba(16,185,129,0.12)' : 'rgba(239,68,68,0.12)';
  const border = available ? 'rgba(16,185,129,0.3)' : 'rgba(239,68,68,0.3)';

  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 5,
        borderRadius: 2,
        border: `1px solid ${border}`,
        background: bg,
        color,
        fontSize: 10,
        fontWeight: 700,
        padding: '3px 8px',
        textTransform: 'uppercase',
        letterSpacing: '0.04em',
      }}
    >
      <span style={{ width: 5, height: 5, borderRadius: 1, background: color, flexShrink: 0 }} />
      {available ? 'Disponible' : 'En livraison'}
    </span>
  );
}

