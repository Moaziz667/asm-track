

import React from 'react';
import { IconSearch as Search, IconX as X } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';

interface RouteFilterBarProps {
  counts: {
    total: number;
    draft: number;
    validated: number;
    inProgress: number;
    closed: number;
  };
  filters: {
    search?: string;
    status?: string;
    city?: string;
    dateFrom?: string;
    dateTo?: string;
  };
  onFilterChange: (key: string, value: string) => void;
  onClearFilters?: () => void;
}

export function RouteFilterBar({
  counts,
  filters,
  onFilterChange,
  onClearFilters,
}: RouteFilterBarProps) {
  const t = useT();
  const hasActiveFilters = Object.values(filters).some(v => Boolean(v));

  const statusFilters = [
    { key: 'all', label: t.routeFilterBar.statusAll, count: counts.total, statusValue: '' },
    { key: 'draft', label: t.routeFilterBar.statusDraft, count: counts.draft, statusValue: 'DRAFT' },
    { key: 'validated', label: t.routeFilterBar.statusValidated, count: counts.validated, statusValue: 'VALIDATED' },
    { key: 'inProgress', label: t.routeFilterBar.statusInProgress, count: counts.inProgress, statusValue: 'IN_PROGRESS' },
    { key: 'closed', label: t.routeFilterBar.statusClosed, count: counts.closed, statusValue: 'CLOSED' },
  ];

  return (
    <div className="space-y-3 bg-white border-b border-slate-200 p-5">
      {/* ── Quick Status Filters ── */}
      <div className="flex items-center gap-2 flex-wrap">
        {statusFilters.map((filter) => (
          <button
            key={filter.key}
            onClick={() => onFilterChange('status', filter.statusValue)}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-semibold transition-colors ${
              filters.status === filter.statusValue
                ? 'bg-slate-800 text-white'
                : 'bg-slate-50 text-slate-700 border border-slate-200 hover:border-slate-300'
            }`}
          >
            {filter.label}
            <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
              filters.status === filter.statusValue
                ? 'bg-white/20'
                : 'bg-slate-200 text-slate-600'
            }`}>
              {filter.count}
            </span>
          </button>
        ))}
      </div>

      {/* ── Search + Filters Row ── */}
      <div className="flex items-center gap-3 flex-wrap">
        {/* Search */}
        <div className="flex-1 min-w-[240px] relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            placeholder={t.placeholders.searchRoutes}
            value={filters.search || ''}
            onChange={(e) => onFilterChange('search', e.target.value)}
            className="w-full h-9 pl-9 pr-3 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-slate-800/20"
          />
        </div>

        {/* City Filter */}
        <input
          type="text"
          placeholder={t.routeFilterBar.cityPlaceholder}
          value={filters.city || ''}
          onChange={(e) => onFilterChange('city', e.target.value)}
          className="h-9 px-3 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-slate-800/20"
        />

        {/* Date From */}
        <input
          type="date"
          value={filters.dateFrom || ''}
          onChange={(e) => onFilterChange('dateFrom', e.target.value)}
          className="h-9 px-3 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-slate-800/20"
        />

        {/* Date To */}
        <input
          type="date"
          value={filters.dateTo || ''}
          onChange={(e) => onFilterChange('dateTo', e.target.value)}
          className="h-9 px-3 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-slate-800/20"
        />

        {/* Clear Filters */}
        {hasActiveFilters && (
          <button
            onClick={onClearFilters}
            className="h-9 px-3 flex items-center gap-1.5 text-xs font-semibold text-slate-600 border border-slate-200 rounded-lg hover:border-slate-300 transition-colors"
          >
            <X size={12} /> {t.routeFilterBar.clearButton}
          </button>
        )}
      </div>
    </div>
  );
}

