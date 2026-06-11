import React, { useState, useEffect, useMemo } from 'react';
import { 
  IconUser, 
  IconRoute, 
  IconSearch, 
  IconAlertTriangle, 
  IconInfoCircle,
  IconCheck
} from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { useT } from '@/lib/LocaleContext';
import { cn } from '@/lib/utils';
import { type DriverOption, type RouteOption } from './ReassignModal';

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';

interface ReassignCommandOverlayProps {
  open: boolean;
  entityName?: string;
  entityLabel?: string;
  entityWeightKg?: number;
  drivers: DriverOption[];
  routes?: RouteOption[];
  currentDriverId?: string;
  loading?: boolean;
  onConfirm: (payload: {
    targetType: 'driver' | 'route';
    targetId: string;
    startTime?: string;
    endTime?: string;
    note?: string;
    stopOrder?: number;
    acknowledgeWarnings?: boolean;
  }) => void;
  onCancel: () => void;
}

type Tab = 'driver' | 'route';

export function ReassignCommandOverlay({
  open,
  entityLabel,
  entityName,
  entityWeightKg = 0,
  drivers,
  routes = [],
  currentDriverId,
  loading = false,
  onConfirm,
  onCancel,
}: ReassignCommandOverlayProps) {
  const t = useT();
  const copy = t.reassignCommandOverlay;

  const label = entityLabel || copy.deliveryLabel;

  const [tab, setTab] = useState<Tab>('driver');
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [note, setNote] = useState('');
  const [stopOrder, setStopOrder] = useState<number | ''>('');
  const [acknowledgeWarnings, setAcknowledgeWarnings] = useState(false);

  useEffect(() => {
    if (!open) {
      setSearch(''); setSelectedId(null); setStartTime('');
      setEndTime(''); setNote(''); setAcknowledgeWarnings(false); setTab('driver');
    }
  }, [open]);

  const filteredDrivers = useMemo(() => {
    const q = search.trim().toLowerCase();
    const list = drivers.filter(d => d.id !== currentDriverId);
    if (!q) return list;
    return list.filter(d => d.name?.toLowerCase().includes(q) || d.phone?.toLowerCase().includes(q));
  }, [drivers, search, currentDriverId]);

  const filteredRoutes = useMemo(() => {
    const q = search.trim().toLowerCase();
    const list = routes.filter(r => r.status !== 'CLOSED' && r.status !== 'CANCELLED');
    if (!q) return list;
    return list.filter(r =>
      r.name.toLowerCase().includes(q) || r.driverName?.toLowerCase().includes(q) || r.city?.toLowerCase().includes(q)
    );
  }, [routes, search]);

  const capacityInfo = useMemo(() => {
    if (!selectedId) return null;
    let capacity = 0, currentLoad = 0;
    if (tab === 'driver') {
      const d = drivers.find(x => x.id === selectedId);
      if (!d) return null;
      capacity = d.vehicleCapacityKg ?? 0; currentLoad = d.currentLoadKg ?? 0;
    } else {
      const r = routes.find(x => x.id === selectedId);
      if (!r) return null;
      capacity = r.capacityKg ?? 0; currentLoad = r.currentLoadKg ?? 0;
    }
    if (!capacity) return { capacity: 0, currentLoad, newLoad: currentLoad + entityWeightKg, pct: 0, over: false };
    const newLoad = currentLoad + entityWeightKg;
    const pct = Math.round((newLoad / capacity) * 100);
    return { capacity, currentLoad, newLoad, pct, over: newLoad > capacity };
  }, [selectedId, tab, drivers, routes, entityWeightKg]);

  const canConfirm = !!selectedId && !loading && (!capacityInfo?.over || acknowledgeWarnings);

  const handleConfirm = () => {
    if (!selectedId || (capacityInfo?.over && !acknowledgeWarnings)) return;
    onConfirm({
      targetType: tab, targetId: selectedId,
      startTime: startTime || undefined, endTime: endTime || undefined,
      note: note.trim() || undefined,
      stopOrder: typeof stopOrder === 'number' ? stopOrder : undefined,
      acknowledgeWarnings,
    });
  };

  const handleTabChange = (val: string) => {
    setTab(val as Tab);
    setSelectedId(null);
  };

  return (
    <Dialog open={open} onOpenChange={(val) => !val && onCancel()}>
      <DialogContent className="max-w-[850px] p-0 overflow-hidden flex flex-col h-auto max-h-[85vh]">
        
        <DialogHeader className="px-6 py-4 border-b border-border bg-muted/30">
          <div className="flex items-center gap-3">
            <DialogTitle className="text-lg font-semibold tracking-tight text-foreground flex items-center gap-2">
              {copy.title}
            </DialogTitle>
            {entityName && (
              <Badge variant="default" className="font-mono bg-primary text-primary-foreground tracking-wider rounded-sm">
                {label}: {entityName}
              </Badge>
            )}
          </div>
          <DialogDescription className="sr-only">
            Select a new driver or route for reassignment.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col md:flex-row flex-1 overflow-hidden min-h-[400px]">
          
          {/* Left Split: Target Selection (60%) */}
          <div className="w-full md:w-[55%] flex flex-col border-r border-border bg-background">
            <Tabs value={tab} onValueChange={handleTabChange} className="flex flex-col h-full w-full">
              {routes.length > 0 && (
                <div className="px-4 pt-3 pb-1 border-b border-border bg-muted/10">
                  <TabsList className="w-full grid grid-cols-2">
                    <TabsTrigger value="driver" className="flex items-center gap-2">
                      <IconUser size={16} />
                      {copy.driverTab}
                    </TabsTrigger>
                    <TabsTrigger value="route" className="flex items-center gap-2">
                      <IconRoute size={16} />
                      {copy.routeTab}
                    </TabsTrigger>
                  </TabsList>
                </div>
              )}

              {/* Search Input */}
              <div className="p-4 border-b border-border bg-background">
                <div className="relative">
                  <IconSearch size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground pointer-events-none" />
                  <Input
                    type="text"
                    placeholder={tab === 'driver' ? copy.searchDriver : copy.searchRoute}
                    value={search}
                    onChange={e => setSearch(e.target.value)}
                    className="pl-10 h-10 w-full bg-muted/30"
                  />
                </div>
              </div>

              {/* Entity List */}
              <ScrollArea className="flex-1 bg-background">
                <div className="p-3 flex flex-col gap-2">
                  <TabsContent value="driver" className="m-0 focus-visible:outline-none">
                    {filteredDrivers.length > 0 ? filteredDrivers.map(d => {
                      const capPct = d.vehicleCapacityKg ? Math.round(((d.currentLoadKg ?? 0) / d.vehicleCapacityKg) * 100) : 0;
                      const selected = selectedId === d.id;
                      return (
                        <div
                          key={d.id}
                          role="button"
                          tabIndex={0}
                          onClick={() => setSelectedId(d.id)}
                          onKeyDown={(e) => e.key === 'Enter' && setSelectedId(d.id)}
                          className={cn(
                            "group flex items-center justify-between p-3 rounded-lg border transition-all cursor-pointer mb-2",
                            selected 
                              ? "border-primary bg-primary/5 ring-1 ring-primary" 
                              : "border-border hover:border-primary/50 hover:bg-muted/50"
                          )}
                        >
                          <div className="flex items-center gap-3 overflow-hidden">
                            <div className={cn(
                              "w-10 h-10 rounded-full flex items-center justify-center shrink-0 border",
                              selected ? "bg-primary text-primary-foreground border-primary" : "bg-muted border-border text-muted-foreground"
                            )}>
                              <IconUser size={18} />
                            </div>
                            <div className="flex flex-col min-w-0">
                              <span className={cn(
                                "text-sm truncate font-medium",
                                selected ? "text-primary font-semibold" : "text-foreground"
                              )}>
                                {d.name || copy.unnamed}
                              </span>
                              <span className="text-xs text-muted-foreground mt-0.5 truncate flex items-center gap-1">
                                {d.phone || '—'} 
                                {d.vehicleCapacityKg && (
                                  <>
                                    <span className="opacity-50">•</span>
                                    <span>{d.currentLoadKg ?? 0}/{d.vehicleCapacityKg}kg ({capPct}%)</span>
                                  </>
                                )}
                              </span>
                            </div>
                          </div>
                          {d.todayRouteId && (
                            <div className="shrink-0 ml-3 flex flex-col items-end">
                              <Badge variant="outline" className="text-2xs tracking-wider bg-background">
                                {d.todayRouteName || copy.activeRoute}
                              </Badge>
                              <span className="text-xs font-medium text-primary mt-1">
                                {d.todayStopCount ?? 0} {copy.stops}
                              </span>
                            </div>
                          )}
                        </div>
                      );
                    }) : (
                      <div className="p-10 text-center text-sm text-muted-foreground">
                        {copy.noResults}
                      </div>
                    )}
                  </TabsContent>

                  <TabsContent value="route" className="m-0 focus-visible:outline-none">
                    {filteredRoutes.length > 0 ? filteredRoutes.map(r => {
                      const selected = selectedId === r.id;
                      return (
                        <div
                          key={r.id}
                          role="button"
                          tabIndex={0}
                          onClick={() => setSelectedId(r.id)}
                          onKeyDown={(e) => e.key === 'Enter' && setSelectedId(r.id)}
                          className={cn(
                            "group flex items-center justify-between p-3 rounded-lg border transition-all cursor-pointer mb-2",
                            selected 
                              ? "border-primary bg-primary/5 ring-1 ring-primary" 
                              : "border-border hover:border-primary/50 hover:bg-muted/50"
                          )}
                        >
                          <div className="flex items-center gap-3 overflow-hidden">
                            <div className={cn(
                              "w-10 h-10 rounded-full flex items-center justify-center shrink-0 border",
                              selected ? "bg-primary text-primary-foreground border-primary" : "bg-muted border-border text-muted-foreground"
                            )}>
                              <IconRoute size={18} />
                            </div>
                            <div className="flex flex-col min-w-0">
                              <div className="flex items-center gap-2">
                                <span className={cn(
                                  "text-sm truncate font-medium",
                                  selected ? "text-primary font-semibold" : "text-foreground"
                                )}>
                                  {r.name}
                                </span>
                                <Badge variant="secondary" className="text-2xs h-5 px-1.5 font-mono">
                                  {r.status}
                                </Badge>
                              </div>
                              <span className="text-xs text-muted-foreground mt-0.5 truncate">
                                {r.driverName || copy.noDriver} • {r.stopCount} {copy.stops} {r.city ? `• ${r.city}` : ''}
                              </span>
                            </div>
                          </div>
                        </div>
                      );
                    }) : (
                      <div className="p-10 text-center text-sm text-muted-foreground">
                        {copy.noResults}
                      </div>
                    )}
                  </TabsContent>
                </div>
              </ScrollArea>
            </Tabs>
          </div>

          {/* Right Split: Parameters Configuration (45%) */}
          <ScrollArea className="w-full md:w-[45%] bg-muted/20">
            <div className="p-6 flex flex-col h-full gap-5">
              
              <div className="flex items-center border-b border-border pb-2">
                <h3 className="text-sm font-semibold text-foreground tracking-tight">
                  {copy.operationParams}
                </h3>
              </div>

              <div className="flex flex-col gap-4">
                {tab === 'route' && selectedId && (
                  <div className="flex flex-col gap-4 bg-background p-4 rounded-lg border border-border">
                    <div className="flex flex-col gap-2">
                      <label className="text-sm font-medium text-foreground">{copy.orderLabel}</label>
                      <Input
                        type="number"
                        min={1}
                        placeholder="e.g. 5"
                        value={stopOrder}
                        onChange={e => setStopOrder(e.target.value ? Number(e.target.value) : '')}
                        className="bg-background"
                      />
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <div className="flex flex-col gap-2">
                        <label className="text-sm font-medium text-foreground">{copy.timeStart}</label>
                        <Input
                          type="time"
                          value={startTime}
                          onChange={e => setStartTime(e.target.value)}
                          className="bg-background font-mono text-sm"
                        />
                      </div>
                      <div className="flex flex-col gap-2">
                        <label className="text-sm font-medium text-foreground">{copy.timeEnd}</label>
                        <Input
                          type="time"
                          value={endTime}
                          onChange={e => setEndTime(e.target.value)}
                          className="bg-background font-mono text-sm"
                        />
                      </div>
                    </div>
                  </div>
                )}

                <div className="flex flex-col gap-2">
                  <label className="text-sm font-medium text-foreground">{copy.noteLabel}</label>
                  <Textarea
                    rows={4}
                    placeholder={copy.notePlaceholder}
                    value={note}
                    onChange={e => setNote(e.target.value)}
                    className="resize-none bg-background focus-visible:ring-primary"
                  />
                </div>

                {capacityInfo && capacityInfo.capacity > 0 && (
                  <div className={cn(
                    "mt-2 border rounded-lg p-4 flex flex-col gap-3",
                    capacityInfo.over 
                      ? "bg-destructive/10 border-destructive/30" 
                      : capacityInfo.pct > 80 
                        ? "bg-amber-500/10 border-amber-500/30" 
                        : "bg-primary/5 border-primary/20"
                  )}>
                    <div className="flex flex-col gap-1.5">
                      <div className="flex items-center gap-2 text-foreground">
                        {capacityInfo.over 
                          ? <IconAlertTriangle size={16} className="text-destructive" />
                          : <IconInfoCircle size={16} className="text-muted-foreground" />
                        }
                        <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                          {copy.capacityTarget}
                        </span>
                      </div>
                      <span className={cn(
                        "text-lg font-bold pl-6",
                        capacityInfo.over ? "text-destructive" : "text-foreground"
                      )}>
                        {capacityInfo.newLoad} / {capacityInfo.capacity} KG ({capacityInfo.pct}%)
                      </span>
                    </div>

                    <div className="w-full h-2.5 bg-muted/50 border border-border overflow-hidden rounded-full mt-1 relative">
                      <div
                        className={cn(
                          "h-full rounded-full transition-all",
                          capacityInfo.over ? "bg-destructive" : capacityInfo.pct > 80 ? "bg-amber-500" : "bg-primary"
                        )}
                        style={{ width: `${Math.min(capacityInfo.pct, 100)}%` }}
                      />
                    </div>

                    {capacityInfo.over && (
                      <label className="flex items-center gap-3 cursor-pointer mt-3 p-2 rounded-md hover:bg-destructive/10 transition-colors group select-none">
                        <div className={cn(
                          "w-5 h-5 rounded-[4px] border flex items-center justify-center transition-colors shadow-sm",
                          acknowledgeWarnings ? "bg-destructive border-destructive text-destructive-foreground" : "border-destructive bg-background group-hover:border-destructive/80"
                        )}>
                          {acknowledgeWarnings && <IconCheck size={14} stroke={3} />}
                        </div>
                        <input 
                          type="checkbox" 
                          checked={acknowledgeWarnings} 
                          onChange={e => setAcknowledgeWarnings(e.target.checked)} 
                          className="sr-only" 
                        />
                        <span className="text-xs font-semibold text-destructive uppercase tracking-wide">
                          {copy.forceWarning}
                        </span>
                      </label>
                    )}
                  </div>
                )}
              </div>
            </div>
          </ScrollArea>
        </div>

        <DialogFooter className="px-6 py-4 border-t border-border bg-background sm:justify-end gap-2">
          <Button 
            variant="outline" 
            onClick={onCancel} 
            disabled={loading}
            className="font-semibold"
          >
            {copy.cancelBtn}
          </Button>
          <Button 
            onClick={handleConfirm} 
            disabled={!canConfirm}
            className={cn(
              "font-semibold min-w-[140px]",
              canConfirm && capacityInfo?.over 
                ? "bg-destructive text-destructive-foreground hover:bg-destructive/90" 
                : ""
            )}
          >
            {loading ? (
               <span className="w-4 h-4 border-2 border-primary-foreground/50 border-t-primary-foreground rounded-full animate-spin mr-2" />
            ) : null}
            {copy.confirmBtn}
          </Button>
        </DialogFooter>

      </DialogContent>
    </Dialog>
  );
}
