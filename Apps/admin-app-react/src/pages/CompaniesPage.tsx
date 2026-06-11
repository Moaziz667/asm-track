

import { useEffect, useState, useCallback, useMemo } from 'react';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { useT } from '@/lib/LocaleContext';
import {
  IconBuilding, IconPlus, IconPencil, IconTrash, IconSearch,
  IconRefresh, IconDatabase,
} from '@tabler/icons-react';
import { AppModal } from '@/components/overlays/AppModal';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

type Company = {
  id: string;
  name: string;
  logoUrl?: string;
  address?: string;
  primaryColor?: string;
  erpType?: string;
  erpApiUrl?: string;
  erpDbName?: string;
  erpUsername?: string;
  supportEmail?: string;
  active: boolean;
  createdAt: string;
};

const ERP_TYPES = ['NONE', 'ODOO', 'SAP', 'CUSTOM'];

const defaultForm = (): Omit<Company, 'id' | 'active' | 'createdAt'> => ({
  name: '',
  address: '',
  primaryColor: 'var(--brand)',
  erpType: 'NONE',
  erpApiUrl: '',
  erpDbName: '',
  erpUsername: '',
  supportEmail: '',
});

export default function CompaniesPage() {
  const t = useT();
  usePageBreadcrumb([{ label: t.sidebar.items.companies || 'Entreprises' }]);

  const [companies, setCompanies] = useState<Company[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [selected, setSelected] = useState<Company | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(defaultForm());
  const [saving, setSaving] = useState(false);
  const [deactivateTarget, setDeactivateTarget] = useState<Company | null>(null);
  const [deactivating, setDeactivating] = useState(false);

  const fetchCompanies = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/api/admin/companies');
      setCompanies(Array.isArray(res.data) ? res.data : []);
    } catch (err: any) {
      showErrorToast(err, 'errorCompaniesLoadFailed');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchCompanies(); }, [fetchCompanies]);

  const filtered = useMemo(() =>
    companies.filter(c =>
      !searchTerm || c.name.toLowerCase().includes(searchTerm.toLowerCase())
    ), [companies, searchTerm]);

  const stats = useMemo(() => ({
    total: companies.length,
    active: companies.filter(c => c.active).length,
  }), [companies]);

  const openCreate = () => {
    setEditingId(null);
    setForm(defaultForm());
    setModalOpen(true);
  };

  const openEdit = (c: Company) => {
    setEditingId(c.id);
    setForm({
      name: c.name,
      address: c.address ?? '',
      primaryColor: c.primaryColor ?? 'var(--brand)',
      erpType: c.erpType ?? 'NONE',
      erpApiUrl: c.erpApiUrl ?? '',
      erpDbName: c.erpDbName ?? '',
      erpUsername: c.erpUsername ?? '',
      supportEmail: c.supportEmail ?? '',
    });
    setModalOpen(true);
  };

  const saveCompany = async () => {
    if (!form.name.trim()) {
      return showErrorToast(null, 'errorCompanyNameRequired');
    }
    setSaving(true);
    try {
      if (editingId) {
        await api.put(`/api/admin/companies/${editingId}`, form);
        showSuccessToast('successCompanyUpdated');
      } else {
        await api.post('/api/admin/companies', { ...form, active: true });
        showSuccessToast('successCompanyCreated');
      }
      setModalOpen(false);
      fetchCompanies();
    } catch (err: any) {
      showErrorToast(err, editingId ? 'errorCompanyUpdateFailed' : 'errorCompanyCreateFailed');
    } finally {
      setSaving(false);
    }
  };

  const doDeactivate = async () => {
    if (!deactivateTarget) return;
    setDeactivating(true);
    try {
      await api.delete(`/api/admin/companies/${deactivateTarget.id}`);
      showSuccessToast('successCompanyDeactivated');
      setDeactivateTarget(null);
      fetchCompanies();
    } catch (err: any) {
      showErrorToast(err, 'errorCompanyDeactivateFailed');
    } finally {
      setDeactivating(false);
    }
  };

  const hasErp = (c: Company) => c.erpType && c.erpType !== 'NONE';

  return (
    <div className="flex flex-col overflow-hidden" style={{ height: 'calc(100vh - 64px)', background: 'var(--app-bg)' }}>
      <div className="flex flex-1 min-h-0 overflow-hidden">

        {/* ── Left Rail ────────────────── */}
        <div className="w-[300px] shrink-0 overflow-y-auto flex flex-col" style={{ borderRight: '1px solid var(--border)', background: 'var(--surface)' }}>
          <div className="p-5 border-b border-[var(--border)]">
            <span className="text-xs font-[500] text-[var(--text-muted)] mb-0.5 block">Ressources humaines</span>
            <h1 className="text-xl font-[600] text-[var(--text-primary)] leading-tight tracking-tight">
              Gestion des <span className="text-[var(--brand)]">entreprises</span>
            </h1>
          </div>

          <div className="flex flex-col gap-3 p-5" style={{ borderBottom: '1px solid var(--border)' }}>
            <button
              type="button"
              onClick={openCreate}
              className="flex items-center justify-center gap-2 h-9 w-full text-xs font-bold rounded-xs transition-colors text-white"
              style={{ background: 'var(--brand)' }}
            >
              <IconPlus size={14} />
              Nouvelle entreprise
            </button>
            <div className="relative">
              <IconSearch size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: 'var(--brand)' }} />
              <input
                className="w-full h-9 pl-9 pr-3 text-xs rounded-xs outline-none focus:ring-1 focus:ring-[var(--brand)]"
                style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
                placeholder="Rechercher..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.currentTarget.value)}
              />
            </div>
            <button
              type="button"
              onClick={fetchCompanies}
              disabled={loading}
              className="flex items-center justify-center gap-2 h-9 w-full text-xs font-semibold rounded-xs border hover:bg-[var(--hover-bg)] transition-colors"
              style={{ borderColor: 'var(--border)', color: 'var(--text-muted)' }}
            >
              <IconRefresh size={14} className={loading ? 'animate-spin' : ''} />
              Actualiser
            </button>
          </div>

          <div className="p-5">
            <p className="text-xs font-bold mb-3" style={{ color: 'var(--text-muted)' }}>Vue d'ensemble</p>
            <div className="grid grid-cols-2 gap-2">
              {[
                { label: 'Total', value: stats.total, color: 'var(--text-primary)' },
                { label: 'Actives', value: stats.active, color: '#10B981' },
              ].map(s => (
                <div key={s.label} className="p-3 rounded-xs relative overflow-hidden" style={{ border: '1px solid var(--border)', background: 'var(--app-bg)' }}>
                  <div className="absolute top-0 left-0 w-[3px] h-full" style={{ background: s.color }} />
                  <p className="text-xs font-bold mb-1" style={{ color: 'var(--text-muted)' }}>{s.label}</p>
                  <p className="text-2xl font-[600] font-mono" style={{ color: s.color }}>{s.value}</p>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* ── Main Table ────────────────────────────────────────── */}
        <div className="flex-1 flex flex-col min-w-0 overflow-hidden" style={{ background: 'var(--app-bg)' }}>
          <div className="flex items-center justify-between px-6 h-14 shrink-0" style={{ borderBottom: '1px solid var(--border)', background: 'var(--surface)' }}>
            <p className="text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>
              <span style={{ color: 'var(--brand)' }}>{filtered.length}</span> entreprise(s)
            </p>
          </div>

          <div className="flex-1 overflow-auto">
            <div style={{ minWidth: 700 }}>
              <table className="w-full border-collapse">
                <thead className="sticky top-0 z-20" style={{ background: 'var(--surface)', borderBottom: '1px solid var(--border)' }}>
                  <tr>
                    {['Entreprise', 'ERP', 'Support', 'Statut', ''].map(h => (
                      <th key={h} className="text-left text-xs font-bold px-4 py-3" style={{ color: 'var(--text-muted)' }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    Array.from({ length: 6 }).map((_, i) => (
                      <tr key={i} className="animate-pulse">
                        <td colSpan={5} className="px-4 py-3">
                          <div className="h-3 rounded w-3/4" style={{ background: 'var(--hover-bg)' }} />
                        </td>
                      </tr>
                    ))
                  ) : filtered.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="text-center py-20 opacity-40">
                        <div className="flex flex-col items-center gap-2">
                          <IconBuilding size={36} style={{ color: 'var(--text-muted)' }} />
                          <p className="text-sm font-semibold" style={{ color: 'var(--text-muted)' }}>Aucune entreprise</p>
                        </div>
                      </td>
                    </tr>
                  ) : filtered.map((c) => (
                    <tr
                      key={c.id}
                      className="group transition-colors hover:bg-[var(--hover-bg)] cursor-pointer"
                      style={{ borderBottom: '1px solid var(--border)' }}
                      onClick={() => setSelected(c)}
                    >
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-3">
                          <div
                            className="w-8 h-8 flex items-center justify-center rounded-[var(--radius)] flex-shrink-0"
                            style={{ background: c.primaryColor ?? 'var(--brand)' }}
                          >
                            <span className="text-sm font-[600] text-white font-mono">
                              {c.name.slice(0, 2).toUpperCase()}
                            </span>
                          </div>
                          <div>
                            <p className="text-sm font-bold" style={{ color: 'var(--text-primary)' }}>{c.name}</p>
                            {c.address && <p className="text-2xs" style={{ color: 'var(--text-muted)' }}>{c.address}</p>}
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        {hasErp(c) ? (
                          <div className="flex items-center gap-1.5">
                            <IconDatabase size={12} style={{ color: '#60A5FA' }} />
                            <span className="text-xs font-semibold" style={{ color: '#60A5FA' }}>{c.erpType}</span>
                          </div>
                        ) : (
                          <span className="text-xs" style={{ color: 'var(--text-muted)' }}>—</span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-xs font-mono" style={{ color: 'var(--text-muted)' }}>
                          {c.supportEmail || '—'}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn(
                          'text-xs font-bold px-1.5 py-0.5 rounded-xs',
                          c.active ? 'text-teal-700 bg-teal-50 border border-teal-200' : 'text-red-600 bg-red-50 border border-red-200'
                        )}>
                          {c.active ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right" onClick={(e) => e.stopPropagation()}>
                        <div className="flex gap-1 justify-end opacity-0 group-hover:opacity-100 transition-opacity">
                          <button
                            type="button"
                            title="Modifier"
                            onClick={() => openEdit(c)}
                            className="w-7 h-7 flex items-center justify-center rounded border hover:bg-[var(--hover-bg)] transition-colors"
                            style={{ borderColor: 'var(--border)', color: 'var(--text-muted)' }}
                          >
                            <IconPencil size={14} />
                          </button>
                          {c.active && (
                            <button
                              type="button"
                              title="Désactiver"
                              onClick={() => setDeactivateTarget(c)}
                              className="w-7 h-7 flex items-center justify-center rounded border border-red-200 text-red-500 hover:bg-red-50 transition-colors"
                            >
                              <IconTrash size={14} />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      {/* ── Create / Edit Modal ───────────────────────────────────────────── */}
      <AppModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editingId ? "Modifier l'entreprise" : 'Nouvelle entreprise'}
        subtitle={editingId ? 'Mise à jour des paramètres' : "Création d'un nouveau tenant"}
        size="lg"
        footer={
          <div className="flex gap-2 justify-end">
            <Button variant="ghost" size="sm" onClick={() => setModalOpen(false)}>Annuler</Button>
            <Button size="sm" onClick={saveCompany} disabled={saving}>
              {saving && (
                <svg className="animate-spin h-3 w-3 mr-1" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                </svg>
              )}
              {editingId ? 'Enregistrer' : 'Créer'}
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-4">
          <p className="text-xs font-bold" style={{ color: 'var(--text-muted)' }}>Identité</p>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-semibold mb-1" style={{ color: 'var(--text-muted)' }}>Nom de l'entreprise *</label>
              <input
                className="w-full h-9 px-3 text-sm rounded-xs outline-none focus:ring-1 focus:ring-[var(--brand)]"
                style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
                placeholder="Société XYZ"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                required
              />
            </div>
            <div>
              <label className="block text-xs font-semibold mb-1" style={{ color: 'var(--text-muted)' }}>Email support</label>
              <input
                className="w-full h-9 px-3 text-sm rounded-xs outline-none focus:ring-1 focus:ring-[var(--brand)]"
                style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
                placeholder="support@entreprise.com"
                value={form.supportEmail}
                onChange={(e) => setForm({ ...form, supportEmail: e.target.value })}
              />
            </div>
          </div>
          <div>
            <label className="block text-xs font-semibold mb-1" style={{ color: 'var(--text-muted)' }}>Adresse</label>
            <input
              className="w-full h-9 px-3 text-sm rounded-xs outline-none focus:ring-1 focus:ring-[var(--brand)]"
              style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
              placeholder="123 Rue de la Livraison, Tunis"
              value={form.address}
              onChange={(e) => setForm({ ...form, address: e.target.value })}
            />
          </div>

          <div className="h-px" style={{ background: 'var(--border)' }} />
          <p className="text-xs font-bold" style={{ color: 'var(--text-muted)' }}>Intégration ERP</p>

          <div>
            <label className="block text-xs font-semibold mb-1" style={{ color: 'var(--text-muted)' }}>Type ERP</label>
            <select
              className="w-full h-9 px-3 text-sm rounded-xs outline-none focus:ring-1 focus:ring-[var(--brand)]"
              style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
              value={form.erpType}
              onChange={(e) => setForm({ ...form, erpType: e.target.value })}
            >
              {ERP_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>

          {form.erpType !== 'NONE' && (
            <div className="flex flex-col gap-3">
              <div>
                <label className="block text-xs font-semibold mb-1" style={{ color: 'var(--text-muted)' }}>URL API ERP</label>
                <input
                  className="w-full h-9 px-3 text-sm rounded-xs outline-none focus:ring-1 focus:ring-[var(--brand)]"
                  style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
                  placeholder="https://erp.entreprise.com"
                  value={form.erpApiUrl}
                  onChange={(e) => setForm({ ...form, erpApiUrl: e.target.value })}
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-semibold mb-1" style={{ color: 'var(--text-muted)' }}>Base de données</label>
                  <input
                    className="w-full h-9 px-3 text-sm rounded-xs outline-none focus:ring-1 focus:ring-[var(--brand)]"
                    style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
                    placeholder="nom_db"
                    value={form.erpDbName}
                    onChange={(e) => setForm({ ...form, erpDbName: e.target.value })}
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold mb-1" style={{ color: 'var(--text-muted)' }}>Utilisateur ERP</label>
                  <input
                    className="w-full h-9 px-3 text-sm rounded-xs outline-none focus:ring-1 focus:ring-[var(--brand)]"
                    style={{ background: 'var(--app-bg)', border: '1px solid var(--border)', color: 'var(--text-primary)' }}
                    placeholder="admin@entreprise.com"
                    value={form.erpUsername}
                    onChange={(e) => setForm({ ...form, erpUsername: e.target.value })}
                  />
                </div>
              </div>
            </div>
          )}
        </div>
      </AppModal>

      {/* ── Deactivate Confirm ──────────────────────────────────────────────── */}
      <ConfirmModal
        open={deactivateTarget !== null}
        title="Désactiver cette entreprise ?"
        description={deactivateTarget ? `L'entreprise ${deactivateTarget.name} et tous ses utilisateurs seront désactivés.` : ''}
        confirmLabel="Désactiver"
        cancelLabel="Annuler"
        variant="danger"
        loading={deactivating}
        onConfirm={doDeactivate}
        onCancel={() => setDeactivateTarget(null)}
      />
    </div>
  );
}

