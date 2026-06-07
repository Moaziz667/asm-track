
import { useEffect, useMemo, type CSSProperties } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useT } from '@/lib/LocaleContext';
import { IconFilter as Filter, IconDeviceFloppy as Save, IconRefresh as RotateCcw } from '@tabler/icons-react';
import {
  FILTER_PRESET_KEYS,
  isOperationalRoute,
  type OperationalFilters,
  useGlobalFilters,
} from '@/lib/global-filters';

const FILTER_KEYS: Array<keyof OperationalFilters> = [
  'dateFrom',
  'dateTo',
  'zone',
  'driver',
  'route',
  'status',
  'search',
];

export default function OperationalFilterBar() {
  const t = useT();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const {
    filters,
    globalContext,
    setFilter,
    clearFilters,
    setGlobalContext,
    savePreset,
    loadPreset,
    applyFilters,
  } = useGlobalFilters();

  const activeChips = useMemo(() => {
    return FILTER_KEYS.filter((key) => filters[key]).map((key) => ({
      key,
      value: filters[key],
    }));
  }, [filters]);

  useEffect(() => {
    if (!pathname || !isOperationalRoute(pathname)) return;
    const next: Partial<OperationalFilters> = {};
    FILTER_KEYS.forEach((key) => {
      const value = searchParams?.get(key);
      if (value !== null && value !== undefined) next[key] = value;
    });
    if (Object.keys(next).length > 0) {
      applyFilters(next);
    }
  }, [applyFilters, searchParams, pathname]);

  useEffect(() => {
    if (!pathname || !searchParams || !isOperationalRoute(pathname)) return;
    const next = new URLSearchParams(searchParams.toString());

    FILTER_KEYS.forEach((key) => {
      const value = globalContext ? filters[key] : '';
      if (!value) {
        next.delete(key);
        return;
      }
      next.set(key, value);
    });

    const nextQuery = next.toString();
    const currentQuery = searchParams.toString();
    if (nextQuery !== currentQuery) {
      navigate(nextQuery ? `${pathname}?${nextQuery}` : pathname, { replace: true });
    }
  }, [filters, globalContext, searchParams, pathname, navigate]);

  if (!pathname || !isOperationalRoute(pathname)) return null;

  return (
    <div
      style={{
        borderBottom: '1px solid rgba(169,180,185,0.15)',
        background: 'rgba(247,249,251,0.85)',
        backdropFilter: 'blur(24px)',
        padding: '10px 16px',
        display: 'grid',
        gap: 10,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: 'var(--muted-foreground)', fontSize: 12, fontWeight: 700 }}>
          <Filter size={14} />
          {t.operationalFilterBar.title}
        </div>

        <label style={{ fontSize: 12, color: 'var(--muted-foreground)', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <input
            type="checkbox"
            checked={globalContext}
            onChange={(e) => setGlobalContext(e.target.checked)}
          />
          {t.operationalFilterBar.persistLabel}
        </label>

        <button
          type="button"
          onClick={clearFilters}
          style={{
            border: 'none',
            background: 'rgba(81,95,116,0.1)',
            color: '#515f74',
            borderRadius: 6,
            padding: '6px 10px',
            fontSize: 12,
            fontWeight: 600,
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            cursor: 'pointer',
          }}
        >
          <RotateCcw size={13} />
          {t.operationalFilterBar.clearButton}
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(8, minmax(120px, 1fr))', gap: 8 }}>
        <input type="date" value={filters.dateFrom} onChange={(e) => setFilter('dateFrom', e.target.value)} style={inputStyle} />
        <input type="date" value={filters.dateTo} onChange={(e) => setFilter('dateTo', e.target.value)} style={inputStyle} />
        <input placeholder={t.operationalFilterBar.zonePlaceholder} value={filters.zone} onChange={(e) => setFilter('zone', e.target.value)} style={inputStyle} />
        <input placeholder={t.operationalFilterBar.driverPlaceholder} value={filters.driver} onChange={(e) => setFilter('driver', e.target.value)} style={inputStyle} />
        <input placeholder={t.operationalFilterBar.routePlaceholder} value={filters.route} onChange={(e) => setFilter('route', e.target.value)} style={inputStyle} />
        <input placeholder={t.operationalFilterBar.statusPlaceholder} value={filters.status} onChange={(e) => setFilter('status', e.target.value)} style={inputStyle} />
        {/* Slot filter removed - not part of OperationalFilters */}
        <input placeholder={t.operationalFilterBar.searchPlaceholder} value={filters.search} onChange={(e) => setFilter('search', e.target.value)} style={inputStyle} />
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        {FILTER_PRESET_KEYS.map((preset) => (
          <button
            key={preset}
            type="button"
            onClick={() => loadPreset(preset)}
            style={{
              border: '1px solid rgba(169,180,185,0.2)',
              borderRadius: 999,
              background: '#fff',
              color: 'var(--muted-foreground)',
              fontSize: 11,
              padding: '4px 10px',
              cursor: 'pointer',
            }}
          >
            {preset === 'morning-dispatch' ? t.operationalFilterBar.presetMorning : preset === 'north-zone' ? t.operationalFilterBar.presetNorth : t.operationalFilterBar.presetAtRisk}
          </button>
        ))}

        <button
          type="button"
          onClick={() => savePreset('morning-dispatch')}
          style={{
            border: 'none',
            background: 'linear-gradient(90deg, #515f74 0%, #455368 100%)',
            color: '#fff',
            borderRadius: 6,
            padding: '6px 10px',
            fontSize: 12,
            fontWeight: 600,
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            cursor: 'pointer',
          }}
        >
          <Save size={13} />
          {t.operationalFilterBar.saveAsMorning}
        </button>

        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginLeft: 'auto' }}>
          {activeChips.map((chip) => (
            <span
              key={chip.key}
              style={{
                background: 'rgba(81,95,116,0.1)',
                color: '#515f74',
                borderRadius: 999,
                padding: '3px 8px',
                fontSize: 11,
                border: '1px solid rgba(169,180,185,0.15)',
              }}
            >
              {chip.key}: {chip.value}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

const inputStyle: CSSProperties = {
  height: 30,
  border: 'none',
  outline: 'none',
  borderRadius: 6,
  padding: '0 10px',
  background: '#ffffff',
  color: 'var(--foreground)',
  fontSize: 12,
  boxShadow: '0 6px 18px rgba(42,52,57,0.04)',
};

