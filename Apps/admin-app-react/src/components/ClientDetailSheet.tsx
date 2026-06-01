import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { Client, Delivery } from '@/types';
import StatusBadge from './StatusBadge';
import Avatar from './Avatar';
import { IconX as X, IconPackage as Package, IconCalendar as Calendar, IconShield as Shield, IconExternalLink as ExternalLink } from '@tabler/icons-react';
import { format, formatDistanceToNow } from 'date-fns';

const mono: React.CSSProperties = { fontFamily: "'JetBrains Mono', monospace" };

interface ClientDetailSheetProps {
  client: Client | null;
  onClose: () => void;
  onCreateDelivery?: (client: { clientId: string; clientName: string; clientPhone: string }) => void;
}

export default function ClientDetailSheet({ client, onClose, onCreateDelivery }: ClientDetailSheetProps) {
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [loadingDeliveries, setLoadingDeliveries] = useState(false);

  useEffect(() => {
    if (!client) return;
    setLoadingDeliveries(true);
    api.get('/api/admin/deliveries', { params: { size: 100 } })
      .then((res) => {
        const list = res.data.content ?? res.data;
        const filtered = (Array.isArray(list) ? list : []).filter(
          (d: Delivery) => d.clientName === client.name || d.clientPhone === client.phone
        );
        setDeliveries(filtered);
      })
      .catch(() => setDeliveries([]))
      .finally(() => setLoadingDeliveries(false));
  }, [client]);

  if (!client) return null;

  const initials = client.name
    .split(' ')
    .slice(0, 2)
    .map((n) => n[0])
    .join('')
    .toUpperCase();

  const totalOrders = deliveries.length;
  const lastOrder = deliveries.length > 0
    ? deliveries.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0]
    : null;

  return (
    <>
      <div
        onClick={onClose}
        style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.3)', zIndex: 50 }}
      />
      <div
        style={{
          position: 'fixed', top: 0, right: 0, bottom: 0, width: 440,
          background: 'var(--surface-1)', zIndex: 51, display: 'flex', flexDirection: 'column',
          boxShadow: '-8px 0 30px rgba(0,0,0,0.18)',
        }}
      >
        {/* Header */}
        <div style={{ padding: '20px', borderBottom: '1px solid var(--border-color)' }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
              <div style={{
                width: 44, height: 44, borderRadius: 2, background: '#10B981',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 15, fontWeight: 700, color: '#fff', flexShrink: 0,
              }}>
                {initials}
              </div>
              <div>
                <h2 style={{ fontSize: 16, fontWeight: 700, color: 'var(--text-strong)', margin: 0 }}>{client.name}</h2>
                <div style={{ ...mono, fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>{client.phone}</div>
              </div>
            </div>
            <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 4, color: 'var(--text-soft)' }}>
              <X size={16} />
            </button>
          </div>

          {/* ERP badge */}
          {client.odooPartnerId ? (
            <span style={{ padding: '3px 8px', borderRadius: 2, fontSize: 10, fontWeight: 700, background: 'rgba(16,185,129,0.12)', color: '#10B981', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              ERP · #{client.odooPartnerId}
            </span>
          ) : (
            <span style={{ padding: '3px 8px', borderRadius: 2, fontSize: 10, fontWeight: 700, background: 'var(--surface-3)', color: 'var(--text-soft)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              Non synchronisé
            </span>
          )}

          <div style={{ fontSize: 11, color: 'var(--text-soft)', marginTop: 8 }}>
            Membre depuis {format(new Date(client.createdAt), 'dd MMM yyyy')}
          </div>
        </div>

        {/* Content */}
        <div style={{ flex: 1, overflowY: 'auto', padding: 20 }}>
          {/* Stats */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8, marginBottom: 20 }}>
            <div style={{ background: 'var(--surface-2)', border: '1px solid var(--border-color)', borderRadius: 2, padding: 12, textAlign: 'center' }}>
              <Package size={14} style={{ color: 'var(--text-soft)', marginBottom: 4 }} />
              <div style={{ ...mono, fontSize: 18, fontWeight: 800, color: 'var(--text-strong)' }}>{totalOrders}</div>
              <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Commandes</div>
            </div>
            <div style={{ background: 'var(--surface-2)', border: '1px solid var(--border-color)', borderRadius: 2, padding: 12, textAlign: 'center' }}>
              <Calendar size={14} style={{ color: 'var(--text-soft)', marginBottom: 4 }} />
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-strong)', marginTop: 2 }}>
                {lastOrder ? formatDistanceToNow(new Date(lastOrder.createdAt), { addSuffix: true }) : 'Jamais'}
              </div>
              <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Dernière</div>
            </div>
            <div style={{ background: 'var(--surface-2)', border: '1px solid var(--border-color)', borderRadius: 2, padding: 12, textAlign: 'center' }}>
              <Shield size={14} style={{ color: client.verified ? '#10B981' : 'var(--text-soft)', marginBottom: 4 }} />
              <div style={{ fontSize: 11, fontWeight: 600, color: client.verified ? '#10B981' : 'var(--text-muted)', marginTop: 2 }}>
                {client.verified ? 'Vérifié' : 'Non vérifié'}
              </div>
              <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Statut</div>
            </div>
          </div>

          {/* Recent deliveries */}
          <div style={{ marginBottom: 20 }}>
            <h3 style={{ fontSize: 11, fontWeight: 700, color: 'var(--text-muted)', marginBottom: 10, textTransform: 'uppercase', letterSpacing: '0.08em' }}>Livraisons récentes</h3>
            {loadingDeliveries ? (
              Array.from({ length: 3 }).map((_, i) => (
                <div key={i} style={{ padding: '8px 0', borderBottom: '1px solid var(--border-subtle)' }}>
                  <div className="skeleton" style={{ width: '60%', height: 11, marginBottom: 4 }} />
                  <div className="skeleton" style={{ width: '30%', height: 9 }} />
                </div>
              ))
            ) : deliveries.length === 0 ? (
              <p style={{ fontSize: 12, color: 'var(--text-soft)' }}>Aucune livraison</p>
            ) : (
              <>
                {deliveries.slice(0, 5).map((d) => (
                  <div
                    key={d.id}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 8,
                      padding: '8px 0', borderBottom: '1px solid var(--border-subtle)',
                      transition: 'background 0.1s',
                    }}
                  >
                    <StatusBadge status={d.status} size="sm" />
                    <span style={{ ...mono, fontSize: 11, fontWeight: 600, color: 'var(--text-strong)' }}>{d.orderId}</span>
                    <span style={{ flex: 1 }} />
                    {d.totalAmount != null && (
                      <span style={{ ...mono, fontSize: 11, color: '#10B981', fontWeight: 700 }}>{d.totalAmount.toFixed(2)}</span>
                    )}
                    <span style={{ fontSize: 10, color: 'var(--text-soft)' }}>
                      {formatDistanceToNow(new Date(d.createdAt), { addSuffix: true })}
                    </span>
                  </div>
                ))}
                {deliveries.length > 5 && (
                  <a
                    href="/routes-table"
                    style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: 'var(--brand-orange)', marginTop: 8, textDecoration: 'none', fontWeight: 600 }}
                  >
                    Voir toutes <ExternalLink size={11} />
                  </a>
                )}
              </>
            )}
          </div>
        </div>

        {/* Footer action */}
        {onCreateDelivery && (
          <div style={{ padding: '14px 20px', borderTop: '1px solid var(--border-color)' }}>
            <button
              onClick={() => {
                onClose();
                onCreateDelivery({ clientId: client.id, clientName: client.name, clientPhone: client.phone });
              }}
              style={{
                width: '100%', padding: '10px 0', borderRadius: 2, border: 'none',
                background: 'var(--brand)', color: '#fff', fontSize: 11, fontWeight: 700,
                cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                textTransform: 'uppercase', letterSpacing: '0.05em', transition: 'opacity 0.15s',
              }}
              onMouseEnter={(e) => (e.currentTarget.style.opacity = '0.88')}
              onMouseLeave={(e) => (e.currentTarget.style.opacity = '1')}
            >
              <Package size={13} /> Créer une livraison
            </button>
          </div>
        )}
      </div>
    </>
  );
}

