'use client';

import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { Button } from '@/components/ui/button';
import { IconRefresh, IconSettings, IconBolt, IconCheck } from '@tabler/icons-react';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { useRouteBuilderContext } from '../hooks/useRouteBuilder';

export function ActionBar() {
  const t = useT();
  const locale = useLocaleStore((state) => state.locale);
  const rb = useRouteBuilderContext();
  const {
    selectedRouteId,
    loading,
    optimizing,
    refreshAll,
    optimizeRouteOrder,
    setConfirmValidateRouteId,
    setSettingsOpen,
  } = rb;

  return (
    <div className="flex items-center gap-1.5">
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => void refreshAll()}
            disabled={loading}
            aria-label={t.actions.refresh}
            className="text-[var(--text-muted)] hover:text-[var(--text-strong)] hover:bg-[var(--hover-bg)]"
          >
            <IconRefresh size={15} className={loading ? 'animate-spin' : ''} />
          </Button>
        </TooltipTrigger>
        <TooltipContent>{t.actions.refresh}</TooltipContent>
      </Tooltip>
 
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => setSettingsOpen(true)}
            disabled={!selectedRouteId}
            aria-label={t.routeBuilderPage.actionBarSettingsLabel}
            className="text-[var(--text-muted)] hover:text-[var(--text-strong)] hover:bg-[var(--hover-bg)]"
          >
            <IconSettings size={15} />
          </Button>
        </TooltipTrigger>
        <TooltipContent>{t.routeBuilderPage.actionBarSettingsTooltip}</TooltipContent>
      </Tooltip>
 
      <div className="h-4 w-[1px] bg-[var(--border)] mx-1" />
 
      <Button
        variant="outline"
        size="sm"
        onClick={() => void optimizeRouteOrder()}
        disabled={!selectedRouteId || optimizing}
        className="h-8 text-xs bg-[var(--surface-2)] border-[var(--border)] text-[var(--text-strong)] hover:bg-[var(--hover-bg)]"
      >
        <IconBolt size={13} className="text-[var(--brand-orange)] mr-1" />
        {optimizing ? t.routeBuilderPage.optimizingProgress : t.routeBuilderPage.optimize}
      </Button>
 
      <Button
        size="sm"
        onClick={() => setConfirmValidateRouteId(selectedRouteId)}
        disabled={!selectedRouteId}
        className="h-8 text-xs bg-[var(--brand-orange)] hover:opacity-90 font-semibold text-white border-transparent"
      >
        <IconCheck size={13} className="mr-1" />
        {t.routeBuilderPage.validate}
      </Button>
    </div>
  );
}
