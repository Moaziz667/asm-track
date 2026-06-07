import { useState, useEffect, useRef } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { useT } from '@/lib/LocaleContext';
import { IconPlus as Plus, IconX as X, IconChevronLeft as ChevronLeft, IconChevronRight as ChevronRight, IconSearch as Search, IconCheck as Check, IconUser as User, IconBuilding as Building2 } from '@tabler/icons-react';
import { AppLoader } from './AppLoader';
import type { ErpClientDTO, ErpProductDTO } from '@/types/erp';
import type { Client } from '@/types';

const mono: React.CSSProperties = { fontFamily: "'JetBrains Mono', monospace" };

interface ClientOption {
  id: string;
  name: string;
  phone: string;
  erpClientId: string | null;
  source: 'system' | 'erp';
}

interface OrderItem {
  name: string;
  sku: string | null;
  quantity: number;
  unitPrice: number;
  unitWeightKg: number;
  erpProductId: string | null;
  stock: number | null;
  productSearch: string;
  productResults: ErpProductDTO[];
  productLoading: boolean;
  selectedProductName: string | null;
}

interface FormClientState {
  clientName: string;
  clientPhone: string;
  erpClientId: string | null;
}

interface NewDeliveryPanelProps {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
  prefillClient?: { clientId: string; clientName: string; clientPhone: string } | null;
}

const STEP_KEYS = ['stepClient', 'stepAddress', 'stepArticles', 'stepPayment', 'stepSummary'] as const;

function useDebouncedValue<T>(value: T, delay = 400): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(id);
  }, [value, delay]);
  return debounced;
}

function useAdminClients(search: string) {
  const [data, setData] = useState<Client[]>([]);
  const [loading, setLoading] = useState(false);
  const debounced = useDebouncedValue(search, 400);

  useEffect(() => {
    if (debounced.trim().length < 2) {
      setData([]);
      setLoading(false);
      return;
    }

    let active = true;
    setLoading(true);
    api
      .get('/api/admin/clients', { params: { search: debounced.trim(), size: 10 } })
      .then((res) => {
        if (!active) return;
        const content = res.data?.content ?? res.data;
        setData(Array.isArray(content) ? content : []);
      })
      .catch(() => {
        if (!active) return;
        setData([]);
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [debounced]);

  return { data, loading };
}

function useErpClients(search: string) {
  const [data, setData] = useState<ErpClientDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const debounced = useDebouncedValue(search, 400);

  useEffect(() => {
    if (debounced.trim().length < 2) {
      setData([]);
      setLoading(false);
      return;
    }

    let active = true;
    setLoading(true);
    api
      .get('/api/admin/erp/clients', { params: { search: debounced.trim(), limit: 10 } })
      .then((res) => {
        if (!active) return;
        setData(Array.isArray(res.data) ? res.data : []);
      })
      .catch(() => {
        if (!active) return;
        setData([]);
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [debounced]);

  return { data, loading };
}

const createEmptyItem = (): OrderItem => ({
  name: '',
  sku: null,
  quantity: 1,
  unitPrice: 0,
  unitWeightKg: 0,
  erpProductId: null,
  stock: null,
  productSearch: '',
  productResults: [],
  productLoading: false,
  selectedProductName: null,
});

export default function NewDeliveryPanel({ open, onClose, onCreated, prefillClient }: NewDeliveryPanelProps) {
  const t = useT();
  const [step, setStep] = useState(prefillClient ? 2 : 1);
  const [submitting, setSubmitting] = useState(false);

  // Step 1 — Client
  const [clientSearch, setClientSearch] = useState('');
  const [showClientDropdown, setShowClientDropdown] = useState(false);
  const [manualClientMode, setManualClientMode] = useState(!prefillClient);
  const [selectedClientId, setSelectedClientId] = useState<string | null>(prefillClient?.clientId ?? null);
  const [client, setClient] = useState<FormClientState>({
    clientName: prefillClient?.clientName ?? '',
    clientPhone: prefillClient?.clientPhone ?? '',
    erpClientId: null,
  });
  const clientDropdownRef = useRef<HTMLDivElement | null>(null);
  const { data: systemClients, loading: systemClientsLoading } = useAdminClients(clientSearch);
  const { data: erpClients, loading: erpClientsLoading } = useErpClients(clientSearch);

  // Step 2 — Address
  const [dropoffAddress, setDropoffAddress] = useState('');
  const [dropoffCity, setDropoffCity] = useState('');
  const [postalCode, setPostalCode] = useState('');
  const [instructions, setInstructions] = useState('');

  // Step 3 — Items
  const [items, setItems] = useState<OrderItem[]>([createEmptyItem()]);
  const itemInputRefs = useRef<Array<HTMLInputElement | null>>([]);
  const productDebounceRefs = useRef<Record<number, ReturnType<typeof setTimeout>>>({});

  // Step 4 — Priority & Scheduling
  const [priority, setPriority] = useState(false);
  const [scheduledAt, setScheduledAt] = useState('');

  const totalQty = items.reduce((s, i) => s + i.quantity, 0);
  const totalWeightKg = items.reduce((s, i) => s + i.quantity * (i.unitWeightKg || 0), 0);
  const totalAmount = items.reduce((s, i) => s + i.quantity * i.unitPrice, 0);

  const showClientLoading = clientSearch.trim().length >= 2 && (systemClientsLoading || erpClientsLoading);

  useEffect(() => {
    const onDocClick = (event: MouseEvent) => {
      if (clientDropdownRef.current && !clientDropdownRef.current.contains(event.target as Node)) {
        setShowClientDropdown(false);
      }
    };

    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, []);

  // Reset when closing
  useEffect(() => {
    if (!open) {
      setStep(prefillClient ? 2 : 1);
      if (!prefillClient) {
        setClientSearch('');
        setShowClientDropdown(false);
        setManualClientMode(true);
        setSelectedClientId(null);
        setClient({ clientName: '', clientPhone: '', erpClientId: null });
      }
      setDropoffAddress('');
      setDropoffCity('');
      setPostalCode('');
      setInstructions('');
      setItems([createEmptyItem()]);
      setPriority(false);
      setScheduledAt('');
    }
  }, [open, prefillClient]);

  const selectClient = (c: ClientOption) => {
    setSelectedClientId(c.id);
    setClient({
      clientName: c.name,
      clientPhone: c.phone,
      erpClientId: c.erpClientId,
    });
    setManualClientMode(false);
    setClientSearch('');
    setShowClientDropdown(false);
  };

  const selectNewClient = () => {
    setSelectedClientId(null);
    setClient((prev) => ({ ...prev, erpClientId: null }));
    setManualClientMode(true);
    setShowClientDropdown(false);
  };

  const canNext = (): boolean => {
    if (step === 1) return client.clientName.trim() !== '' && client.clientPhone.trim() !== '';
    if (step === 2) return dropoffAddress.trim() !== '' && dropoffCity.trim() !== '';
    if (step === 3) return items.length > 0 && items.every((i) => i.name.trim() !== '' && i.quantity > 0);
    if (step === 4) return true;
    return true;
  };

  const addItem = () => {
    const nextIndex = items.length;
    setItems([...items, createEmptyItem()]);
    requestAnimationFrame(() => {
      itemInputRefs.current[nextIndex]?.focus();
    });
  };

  const removeItem = (idx: number) => {
    if (items.length <= 1) return;
    setItems(items.filter((_, i) => i !== idx));
    delete productDebounceRefs.current[idx];
  };

  const updateItem = (idx: number, field: keyof OrderItem, value: string | number | null | ErpProductDTO[] | boolean) => {
    const updated = [...items];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (updated[idx] as any)[field] = value;
    setItems(updated);
  };

  const searchProducts = (idx: number, search: string) => {
    updateItem(idx, 'productSearch', search);

    const timeout = productDebounceRefs.current[idx];
    if (timeout) clearTimeout(timeout);

    if (search.trim().length < 2) {
      updateItem(idx, 'productResults', []);
      updateItem(idx, 'productLoading', false);
      return;
    }

    updateItem(idx, 'productLoading', true);
    productDebounceRefs.current[idx] = setTimeout(async () => {
      try {
        const res = await api.get('/api/admin/erp/products', { params: { search: search.trim(), limit: 10 } });
        const results = Array.isArray(res.data) ? (res.data as ErpProductDTO[]) : [];
        const refreshed = [...items];
        if (!refreshed[idx]) return;
        refreshed[idx].productResults = results;
        refreshed[idx].productLoading = false;
        setItems(refreshed);
      } catch {
        const refreshed = [...items];
        if (!refreshed[idx]) return;
        refreshed[idx].productResults = [];
        refreshed[idx].productLoading = false;
        setItems(refreshed);
      }
    }, 400);
  };

  const selectProduct = (idx: number, product: ErpProductDTO) => {
    const updated = [...items];
    if (!updated[idx]) return;
    updated[idx].erpProductId = product.erpProductId;
    updated[idx].name = product.name;
    updated[idx].sku = product.sku ?? null;
    updated[idx].unitPrice = product.price ?? 0;
    updated[idx].unitWeightKg = product.weightKg ?? 0;
    updated[idx].stock = product.stock ?? 0;
    updated[idx].selectedProductName = product.name;
    updated[idx].productSearch = product.name;
    updated[idx].productResults = [];
    updated[idx].productLoading = false;
    setItems(updated);
  };

  const clearSelectedProduct = (idx: number) => {
    const updated = [...items];
    if (!updated[idx]) return;
    updated[idx].erpProductId = null;
    updated[idx].selectedProductName = null;
    updated[idx].stock = null;
    updated[idx].productSearch = '';
    updated[idx].productResults = [];
    setItems(updated);
  };

  const handleSubmit = async () => {
    setSubmitting(true);
    try {
      const body = {
        clientId: selectedClientId,
        clientName: client.clientName,
        clientPhone: client.clientPhone,
        erpClientId: client.erpClientId,
        dropoffAddress,
        dropoffCity,
        dropoffPostalCode: postalCode || null,
        deliveryInstructions: instructions || null,
        items: items.map((i) => ({
          name: i.name,
          sku: i.sku || null,
          quantity: i.quantity,
          unitPrice: i.unitPrice,
          unitWeightKg: i.unitWeightKg || 0,
          quantityDone: 0,
        })),
        totalAmount,
        currency: 'TND',
        priority: priority ? 'HIGH' : 'NORMAL',
        scheduledAt: scheduledAt || null,
      };

      await api.post('/api/orders', body);
      showSuccessToast('successDeliveryCreated');
      onCreated();
      onClose();
    } catch (err: any) {
      showErrorToast(err, 'errorDeliveryCreateFailed');
    } finally {
      setSubmitting(false);
    }
  };

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div
        onClick={onClose}
        style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.3)',
          zIndex: 50, transition: 'opacity 0.2s',
        }}
      />
      {/* Panel */}
      <div
        style={{
          position: 'fixed', top: 0, right: 0, bottom: 0, width: 520,
          background: 'var(--surface-1)', zIndex: 51, display: 'flex', flexDirection: 'column',
          boxShadow: '-8px 0 30px rgba(0,0,0,0.18)',
        }}
      >
        {/* Header */}
        <div style={{ padding: '18px 20px', borderBottom: '1px solid var(--border-color)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <h2 style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-strong)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Nouvelle livraison</h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', padding: 4, color: 'var(--text-soft)' }}>
            <X size={16} />
          </button>
        </div>

        {/* Progress dots */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '12px 20px', borderBottom: '1px solid var(--border-subtle)' }}>
          {STEPS.map((label, i) => (
            <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <div style={{
                width: 24, height: 24, borderRadius: '50%', display: 'flex',
                alignItems: 'center', justifyContent: 'center', fontSize: 11, fontWeight: 600,
                background: i + 1 <= step ? '#16a34a' : '#e5e7eb',
                color: i + 1 <= step ? '#fff' : '#6b7280',
              }}>
                {i + 1 < step ? <Check size={12} /> : i + 1}
              </div>
              <span style={{ fontSize: 11, color: i + 1 === step ? 'var(--text-strong)' : 'var(--text-soft)', fontWeight: i + 1 === step ? 700 : 400 }}>
                {label}
              </span>
              {i < STEPS.length - 1 && <div style={{ width: 16, height: 1, background: '#e5e7eb' }} />}
            </div>
          ))}
        </div>

        {/* Content */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '20px 20px' }}>
          {/* Step 1: Client */}
          {step === 1 && (
            <div>
              <label style={{ fontSize: 13, fontWeight: 600, color: '#374151', marginBottom: 6, display: 'block' }}>{t.newDeliveryPanel.searchClient}</label>
              <div ref={clientDropdownRef} style={{ position: 'relative', marginBottom: 12 }}>
                <Search size={14} color="#9ca3af" style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)' }} />
                <input
                  value={clientSearch}
                  onChange={(e) => {
                    setClientSearch(e.target.value);
                    setShowClientDropdown(true);
                  }}
                  onFocus={() => setShowClientDropdown(true)}
                  placeholder={t.newDeliveryPanel.searchClientPlaceholder}
                  style={{ width: '100%', padding: '9px 10px 9px 32px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 13, outline: 'none' }}
                  onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')}
                />
                {showClientLoading && <div className="absolute right-3 top-1/2 -translate-y-1/2"><AppLoader size="sm" /></div>}

                {showClientDropdown && (
                  <div style={{ position: 'absolute', top: 'calc(100% + 6px)', left: 0, right: 0, border: '1px solid var(--border-color)', borderRadius: 2, background: 'var(--surface-1)', maxHeight: 280, overflowY: 'auto', zIndex: 60, boxShadow: '0 8px 24px rgba(0,0,0,0.14)' }}>
                    {showClientLoading && (
                      <div style={{ padding: 12 }}>
                        <div style={{ height: 10, borderRadius: 4, background: '#f3f4f6', marginBottom: 8 }} />
                        <div style={{ height: 10, borderRadius: 4, background: '#f3f4f6', marginBottom: 8 }} />
                        <div style={{ height: 10, borderRadius: 4, background: '#f3f4f6' }} />
                      </div>
                    )}

                    {!showClientLoading && clientSearch.trim().length >= 2 && (
                      <>
                        {systemClients.length > 0 && (
                          <>
                            <div style={{ padding: '8px 12px', fontSize: 11, fontWeight: 700, color: '#6b7280', borderBottom: '1px solid #f3f4f6' }}>{t.newDeliveryPanel.systemClients}</div>
                            {systemClients.map((c) => (
                              <div
                                key={`sys-${c.id}`}
                                onMouseDown={(e) => e.preventDefault()}
                                onClick={() =>
                                  selectClient({
                                    id: c.id,
                                    name: c.name,
                                    phone: c.phone,
                                    erpClientId: c.odooPartnerId ? String(c.odooPartnerId) : null,
                                    source: 'system',
                                  })
                                }
                                style={{ padding: '10px 12px', cursor: 'pointer', borderBottom: '1px solid #f3f4f6', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
                              >
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                  <User size={14} color="#6b7280" />
                                  <span style={{ fontSize: 13, color: '#374151' }}>{c.name} — {c.phone}</span>
                                </div>
                                <span style={{ fontSize: 10, color: '#6b7280', background: '#f3f4f6', borderRadius: 9999, padding: '2px 8px' }}>Systeme</span>
                              </div>
                            ))}
                          </>
                        )}

                        {erpClients.length > 0 && (
                          <>
                            <div style={{ padding: '8px 12px', fontSize: 11, fontWeight: 700, color: '#2563eb', borderBottom: '1px solid #f3f4f6' }}>Clients ERP</div>
                            {erpClients.map((c) => (
                              <div
                                key={`erp-${c.erpClientId}`}
                                onMouseDown={(e) => e.preventDefault()}
                                onClick={() =>
                                  selectClient({
                                    id: `erp-${c.erpClientId}`,
                                    name: c.name,
                                    phone: c.phone ?? '',
                                    erpClientId: c.erpClientId,
                                    source: 'erp',
                                  })
                                }
                                style={{ padding: '10px 12px', cursor: 'pointer', borderBottom: '1px solid #f3f4f6', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
                              >
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                  <Building2 size={14} color="#2563eb" />
                                  <span style={{ fontSize: 13, color: '#374151' }}>{c.name} — {c.phone ?? 'N/A'}</span>
                                </div>
                                <span style={{ fontSize: 10, color: '#2563eb', background: '#dbeafe', borderRadius: 9999, padding: '2px 8px' }}>ERP</span>
                              </div>
                            ))}
                          </>
                        )}

                        {systemClients.length === 0 && erpClients.length === 0 && (
                          <div style={{ padding: 12, fontSize: 12, color: '#6b7280' }}>Aucun client trouve</div>
                        )}
                      </>
                    )}

                    <div
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={selectNewClient}
                      style={{ padding: '10px 12px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8, borderTop: '1px solid #e5e7eb', background: '#f9fafb' }}
                    >
                      <Plus size={14} color="#16a34a" />
                      <span style={{ fontSize: 12, color: '#166534', fontWeight: 600 }}>+ Nouveau client</span>
                    </div>
                  </div>
                )}
              </div>

              {!manualClientMode && client.clientName && (
                <div style={{ marginBottom: 12, padding: '10px 12px', borderRadius: 6, background: '#f0fdf4', border: '1px solid #bbf7d0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Check size={14} color="#16a34a" />
                    <div>
                      <div style={{ fontSize: 13, color: '#166534', fontWeight: 600 }}>{client.clientName}</div>
                      <div style={{ fontSize: 12, color: '#166534' }}>{client.clientPhone}</div>
                    </div>
                  </div>
                  <button
                    onClick={selectNewClient}
                    style={{ border: 'none', background: 'none', color: '#166534', fontSize: 12, cursor: 'pointer', textDecoration: 'underline' }}
                  >
                    Effacer
                  </button>
                </div>
              )}

              {manualClientMode && (
                <>
                  <div style={{ marginBottom: 12 }}>
                    <label style={{ fontSize: 12, fontWeight: 500, color: '#374151', marginBottom: 4, display: 'block' }}>Nom *</label>
                    <input
                      value={client.clientName}
                      onChange={(e) => {
                        setClient((prev) => ({ ...prev, clientName: e.target.value, erpClientId: null }));
                        setSelectedClientId(null);
                      }}
                      style={{ width: '100%', padding: '9px 12px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 13, outline: 'none' }}
                      onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                      onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')}
                    />
                  </div>
                  <div>
                    <label style={{ fontSize: 12, fontWeight: 500, color: '#374151', marginBottom: 4, display: 'block' }}>Telephone *</label>
                    <input
                      value={client.clientPhone}
                      onChange={(e) => {
                        setClient((prev) => ({ ...prev, clientPhone: e.target.value, erpClientId: null }));
                        setSelectedClientId(null);
                      }}
                      type="tel"
                      style={{ width: '100%', padding: '9px 12px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 13, outline: 'none' }}
                      onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                      onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')}
                    />
                  </div>
                </>
              )}
            </div>
          )}

          {/* Step 2: Address */}
          {step === 2 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div>
                <label style={{ fontSize: 12, fontWeight: 500, color: '#374151', marginBottom: 4, display: 'block' }}>Adresse complete *</label>
                <textarea
                  value={dropoffAddress}
                  onChange={(e) => setDropoffAddress(e.target.value)}
                  rows={3}
                  style={{ width: '100%', padding: '9px 12px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 13, outline: 'none', resize: 'vertical' }}
                  onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                  onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')}
                />
              </div>
              <div style={{ display: 'flex', gap: 12 }}>
                <div style={{ flex: 1 }}>
                  <label style={{ fontSize: 12, fontWeight: 500, color: '#374151', marginBottom: 4, display: 'block' }}>Ville *</label>
                  <input value={dropoffCity} onChange={(e) => setDropoffCity(e.target.value)}
                    style={{ width: '100%', padding: '9px 12px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 13, outline: 'none' }}
                    onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                    onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')} />
                </div>
                <div style={{ width: 120 }}>
                  <label style={{ fontSize: 12, fontWeight: 500, color: '#374151', marginBottom: 4, display: 'block' }}>Code postal</label>
                  <input value={postalCode} onChange={(e) => setPostalCode(e.target.value)}
                    style={{ width: '100%', padding: '9px 12px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 13, outline: 'none' }}
                    onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                    onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')} />
                </div>
              </div>
              <div>
                <label style={{ fontSize: 12, fontWeight: 500, color: '#374151', marginBottom: 4, display: 'block' }}>{t.newDeliveryPanel.deliveryInstructions}</label>
                <textarea
                  value={instructions}
                  onChange={(e) => setInstructions(e.target.value)}
                  rows={2}
                  placeholder={t.newDeliveryPanel.instructionsPlaceholder}
                  style={{ width: '100%', padding: '9px 12px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 13, outline: 'none', resize: 'vertical' }}
                  onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                  onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')}
                />
              </div>
            </div>
          )}

          {/* Step 3: Items */}
          {step === 3 && (
            <div>
              {items.map((item, idx) => (
                <div key={idx} style={{ marginBottom: 10, padding: '10px 0', borderBottom: '1px solid #f3f4f6' }}>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
                    <div style={{ flex: 1, position: 'relative' }}>
                      {idx === 0 && <label style={{ fontSize: 11, color: '#6b7280', marginBottom: 2, display: 'block' }}>{t.newDeliveryPanel.productLabel} *</label>}
                      <input
                        ref={(el) => {
                          itemInputRefs.current[idx] = el;
                        }}
                        value={item.productSearch}
                        onChange={(e) => searchProducts(idx, e.target.value)}
                        placeholder={t.newDeliveryPanel.productPlaceholder}
                        style={{ width: '100%', padding: '7px 10px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 12, outline: 'none' }}
                        onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                        onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')}
                      />

                      {item.productLoading && (
                        <div className={`absolute right-3 ${idx === 0 ? "top-[30px]" : "top-[10px]"}`}><AppLoader size="sm" /></div>
                      )}

                      {item.productSearch.trim().length >= 2 && item.productResults.length > 0 && (
                        <div style={{ position: 'absolute', top: 'calc(100% + 4px)', left: 0, right: 0, border: '1px solid var(--border-color)', borderRadius: 2, background: 'var(--surface-1)', maxHeight: 180, overflowY: 'auto', zIndex: 50, boxShadow: '0 8px 24px rgba(0,0,0,0.14)' }}>
                          {item.productResults.map((product) => (
                            (() => {
                              const stock = product.stock ?? 0;
                              return (
                            <div
                              key={product.erpProductId}
                              onMouseDown={(e) => e.preventDefault()}
                              onClick={() => selectProduct(idx, product)}
                              style={{ padding: '8px 10px', cursor: 'pointer', borderBottom: '1px solid #f3f4f6' }}
                            >
                              <div style={{ fontSize: 12, color: 'var(--text-strong)', fontWeight: 600 }}>{product.name}</div>
                              <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 2 }}>
                                <span style={{ ...mono, fontSize: 11, color: '#6b7280' }}>{product.sku || 'SKU N/A'}</span>
                                <span style={{ ...mono, fontSize: 11, color: '#16a34a' }}>{(product.price ?? 0).toFixed(3)} TND</span>
                              </div>
                              <div style={{ marginTop: 4 }}>
                                {stock > 0 ? (
                                  <span style={{ fontSize: 10, color: '#166534', background: '#dcfce7', borderRadius: 9999, padding: '2px 8px' }}>{stock} {t.newDeliveryPanel.inStock}</span>
                                ) : (
                                  <span style={{ fontSize: 10, color: '#991b1b', background: '#fee2e2', borderRadius: 9999, padding: '2px 8px' }}>{t.newDeliveryPanel.outOfStock}</span>
                                )}
                              </div>
                            </div>
                              );
                            })()
                          ))}
                        </div>
                      )}

                      {item.productSearch.trim().length >= 2 && !item.productLoading && item.productResults.length === 0 && (
                        <div style={{ position: 'absolute', top: 'calc(100% + 4px)', left: 0, right: 0, border: '1px solid #e5e7eb', borderRadius: 6, background: '#fff', padding: '8px 10px', fontSize: 11, color: '#6b7280', zIndex: 50 }}>
                          {t.newDeliveryPanel.noProductFound}
                        </div>
                      )}
                    </div>

                  </div>

                  <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', marginTop: 8 }}>
                    <div style={{ width: 60 }}>
                    {idx === 0 && <label style={{ fontSize: 11, color: '#6b7280', marginBottom: 2, display: 'block' }}>{t.newDeliveryPanel.quantityLabel}</label>}
                    <input type="number" min={1} value={item.quantity} onChange={(e) => updateItem(idx, 'quantity', Math.max(1, Number(e.target.value)))}
                      style={{ width: '100%', padding: '7px 6px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 12, outline: 'none', textAlign: 'center' }}
                      onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                      onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')} />
                  </div>
                  <div style={{ width: 90 }}>
                    {idx === 0 && <label style={{ fontSize: 11, color: '#6b7280', marginBottom: 2, display: 'block' }}>{t.newDeliveryPanel.priceLabel}</label>}
                    <input type="number" step={0.001} min={0} value={item.unitPrice} onChange={(e) => updateItem(idx, 'unitPrice', Number(e.target.value))}
                      style={{ width: '100%', padding: '7px 6px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 12, outline: 'none', ...mono }}
                      onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                      onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')} />
                  </div>
                  <div style={{ width: 70 }}>
                    {idx === 0 && <label style={{ fontSize: 11, color: '#6b7280', marginBottom: 2, display: 'block' }}>{t.newDeliveryPanel.skuLabel}</label>}
                    <input value={item.sku ?? ''} onChange={(e) => updateItem(idx, 'sku', e.target.value || null)}
                      placeholder={t.newDeliveryPanel.skuPlaceholder}
                      style={{ width: '100%', padding: '7px 6px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 12, outline: 'none' }}
                      onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                      onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')} />
                  </div>
                    <button
                      onClick={() => clearSelectedProduct(idx)}
                      style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 4, opacity: item.selectedProductName ? 1 : 0.3 }}
                    >
                      <Search size={14} color="#6b7280" />
                    </button>
                  <button onClick={() => removeItem(idx)} style={{ background: 'none', border: 'none', cursor: items.length <= 1 ? 'not-allowed' : 'pointer', padding: 4, opacity: items.length <= 1 ? 0.3 : 1 }}>
                    <X size={14} color="#ef4444" />
                  </button>
                </div>

                  {item.selectedProductName && (
                    <div style={{ marginTop: 6, fontSize: 11, color: '#6b7280' }}>
                      {t.newDeliveryPanel.stockAvailable.replace('{stock}', String(item.stock ?? 0))}
                    </div>
                  )}
                  {item.stock !== null && item.quantity > item.stock && (
                    <div style={{ marginTop: 4, fontSize: 11, color: '#92400e', background: '#fef3c7', borderRadius: 6, padding: '4px 8px', display: 'inline-block' }}>
                      {t.newDeliveryPanel.stockWarning}
                    </div>
                  )}
                  <div style={{ marginTop: 4, fontSize: 11, color: '#6b7280' }}>
                    Total ligne: <span style={mono}>{(item.quantity * item.unitPrice).toFixed(3)} TND</span>
                  </div>
                </div>
              ))}
              <button onClick={addItem}
                style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px', borderRadius: 6, border: '1px solid #e5e7eb', background: '#fff', fontSize: 12, fontWeight: 500, color: '#374151', cursor: 'pointer', marginTop: 8 }}>
                <Plus size={13} /> Ajouter un article
              </button>
              <div style={{ marginTop: 16, padding: '12px 14px', borderRadius: 8, background: '#f0fdf4', border: '1px solid #bbf7d0' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, color: '#374151', marginBottom: 4 }}>
                  <span>Total articles: {totalQty}</span>
                  <span>Poids total: {totalWeightKg.toFixed(3)} kg</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 15, fontWeight: 700, color: '#16a34a' }}>
                  <span>Montant total:</span>
                  <span style={mono}>{totalAmount.toFixed(3)} TND</span>
                </div>
              </div>
            </div>
          )}

          {/* Step 4: Payment */}
          {step === 4 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 14px', borderRadius: 6, border: '1px solid #e5e7eb' }}>
                <span style={{ fontSize: 13, fontWeight: 500, color: '#374151' }}>Priorite haute</span>
                <button onClick={() => setPriority(!priority)}
                  style={{
                    width: 44, height: 24, borderRadius: 12, border: 'none', cursor: 'pointer',
                    background: priority ? '#16a34a' : '#d1d5db', position: 'relative', transition: 'background 0.2s',
                  }}>
                  <div style={{
                    width: 18, height: 18, borderRadius: '50%', background: '#fff',
                    position: 'absolute', top: 3, left: priority ? 23 : 3, transition: 'left 0.2s',
                    boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
                  }} />
                </button>
              </div>
              <div>
                <label style={{ fontSize: 12, fontWeight: 500, color: '#374151', marginBottom: 4, display: 'block' }}>Livraison planifiee (optionnel)</label>
                <input type="datetime-local" value={scheduledAt} onChange={(e) => setScheduledAt(e.target.value)}
                  style={{ width: '100%', padding: '9px 12px', border: '1px solid #e5e7eb', borderRadius: 6, fontSize: 13, outline: 'none' }}
                  onFocus={(e) => (e.target.style.borderColor = '#16a34a')}
                  onBlur={(e) => (e.target.style.borderColor = '#e5e7eb')} />
              </div>
            </div>
          )}

          {/* Step 5: Review */}
          {step === 5 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div style={{ background: 'var(--surface-2)', borderRadius: 2, padding: 14, border: '1px solid var(--border-subtle)' }}>
                <h4 style={{ fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.08em' }}>Client</h4>
                <div style={{ fontSize: 13, color: '#374151' }}>{client.clientName}</div>
                <div style={{ fontSize: 12, color: 'var(--text-muted)', ...mono }}>{client.clientPhone}</div>
                <button onClick={() => setStep(1)} style={{ fontSize: 11, color: '#16a34a', background: 'none', border: 'none', cursor: 'pointer', marginTop: 4 }}>Modifier</button>
              </div>
              <div style={{ background: 'var(--surface-2)', borderRadius: 2, padding: 14, border: '1px solid var(--border-subtle)' }}>
                <h4 style={{ fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.08em' }}>Adresse</h4>
                <div style={{ fontSize: 13, color: '#374151' }}>{dropoffAddress}</div>
                <div style={{ fontSize: 12, color: '#6b7280' }}>{dropoffCity} {postalCode}</div>
                {instructions && <div style={{ fontSize: 12, color: '#9ca3af', marginTop: 4 }}>{instructions}</div>}
                <button onClick={() => setStep(2)} style={{ fontSize: 11, color: '#16a34a', background: 'none', border: 'none', cursor: 'pointer', marginTop: 4 }}>Modifier</button>
              </div>
              <div style={{ background: 'var(--surface-2)', borderRadius: 2, padding: 14, border: '1px solid var(--border-subtle)' }}>
                <h4 style={{ fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.08em' }}>Articles ({totalQty})</h4>
                {items.map((item, i) => (
                  <div key={i} style={{ fontSize: 12, color: '#374151', padding: '3px 0' }}>
                    {item.name} × {item.quantity} — <span style={mono}>{(item.quantity * item.unitPrice).toFixed(3)} TND</span>
                  </div>
                ))}
                <div style={{ marginTop: 8, fontSize: 14, fontWeight: 700, color: '#16a34a', ...mono }}>{totalAmount.toFixed(3)} TND</div>
                <button onClick={() => setStep(3)} style={{ fontSize: 11, color: '#16a34a', background: 'none', border: 'none', cursor: 'pointer', marginTop: 4 }}>Modifier</button>
              </div>
              {priority && (
                <div style={{ background: 'var(--surface-2)', borderRadius: 2, padding: 14, border: '1px solid var(--border-subtle)' }}>
                  <span style={{ display: 'inline-block', padding: '2px 8px', borderRadius: 9999, background: '#fef2f2', color: '#ef4444', fontSize: 11, fontWeight: 600 }}>
                    Priorite haute
                  </span>
                  <button onClick={() => setStep(4)} style={{ fontSize: 11, color: '#16a34a', background: 'none', border: 'none', cursor: 'pointer', marginTop: 8, display: 'block' }}>Modifier</button>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div style={{
          padding: '14px 20px', borderTop: '1px solid #e5e7eb', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          background: '#fff',
        }}>
          <span style={{ fontSize: 12, color: '#9ca3af' }}>Etape {step}/{STEPS.length}</span>
          <div style={{ display: 'flex', gap: 8 }}>
            {step > 1 && (
              <button onClick={() => setStep(step - 1)}
                style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '8px 16px', borderRadius: 6, border: '1px solid #e5e7eb', background: '#fff', fontSize: 13, fontWeight: 500, color: '#374151', cursor: 'pointer' }}>
                <ChevronLeft size={14} /> Precedent
              </button>
            )}
            {step < 5 ? (
              <button onClick={() => canNext() && setStep(step + 1)}
                disabled={!canNext()}
                style={{
                  display: 'flex', alignItems: 'center', gap: 4, padding: '8px 16px', borderRadius: 6,
                  border: 'none', background: canNext() ? '#16a34a' : '#d1d5db',
                  fontSize: 13, fontWeight: 600, color: '#fff', cursor: canNext() ? 'pointer' : 'not-allowed',
                }}>
                Suivant <ChevronRight size={14} />
              </button>
            ) : (
              <button onClick={handleSubmit} disabled={submitting}
                style={{
                  display: 'flex', alignItems: 'center', gap: 6, padding: '8px 20px', borderRadius: 6,
                  border: 'none', background: '#16a34a', fontSize: 13, fontWeight: 600, color: '#fff',
                  cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1,
                }}>
                {submitting && <AppLoader size="sm" />}
                Creer la livraison
              </button>
            )}
          </div>
        </div>
      </div>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </>
  );
}

