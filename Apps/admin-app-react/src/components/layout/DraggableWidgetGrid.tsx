import React, { useState, useEffect } from 'react';
import { Responsive, WidthProvider, Layout, LayoutItem, ResponsiveLayouts } from 'react-grid-layout/legacy';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { IconLayoutDashboard } from '@tabler/icons-react';

import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';
import { cn } from '@/lib/utils';

const ResponsiveGridLayout = WidthProvider(Responsive);

// ── Types ─────────────────────────────────────────────────────────────────────

export interface WidgetItem {
  id: string;
  children: React.ReactNode;
  className?: string;
  defaultLayout?: {
    w: number;
    h: number;
    x?: number;
    y?: number;
    minW?: number;
    minH?: number;
  };
}

interface DraggableWidgetGridProps {
  storageKey: string;
  items: WidgetItem[];
  className?: string;
  cols?: { lg: number; md: number; sm: number; xs: number; xxs: number };
  rowHeight?: number;
}

// ── Drag handle (Cloudscape Grip) ─────────────────────────────────────────────

function DragHandleIcon() {
  const t = useT();
  return (
    <div
      className="react-grid-dragHandle absolute top-[14px] start-[14px] z-20 w-6 h-6 flex items-center justify-center cursor-grab active:cursor-grabbing select-none text-[var(--text-soft)] hover:text-[var(--text-primary)] transition-colors"
      title={t.tooltips?.reposition ?? 'Drag to reposition'}
      onPointerDown={e => e.stopPropagation()}
    >
      <svg width="8" height="14" viewBox="0 0 8 14" fill="currentColor">
        <circle cx="2" cy="2" r="1.5" /><circle cx="6" cy="2" r="1.5" />
        <circle cx="2" cy="7" r="1.5" /><circle cx="6" cy="7" r="1.5" />
        <circle cx="2" cy="12" r="1.5" /><circle cx="6" cy="12" r="1.5" />
      </svg>
    </div>
  );
}

// ── Main export ───────────────────────────────────────────────────────────────

export function DraggableWidgetGrid({
  storageKey,
  items,
  className,
  cols = { lg: 12, md: 10, sm: 6, xs: 4, xxs: 2 },
  rowHeight = 40, // Slightly denser rows for Cloudscape
}: DraggableWidgetGridProps) {
  const t = useT();
  const { locale } = useLocaleStore();
  const isRtl = locale === 'ar';
  const dirSuffix = isRtl ? '-rtl-v2' : '-ltr';
  const fullStorageKey = `widget-grid:${storageKey}${dirSuffix}`;
  
  const [isDragging, setIsDragging] = useState(false);
  const [layouts, setLayouts] = useState<ResponsiveLayouts>(() => {
    try {
      const stored = localStorage.getItem(fullStorageKey);
      if (stored) {
        return JSON.parse(stored) as ResponsiveLayouts;
      }
    } catch { /* ignore */ }
    return {};
  });

  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  const handleLayoutChange = (currentLayout: Layout, allLayouts: ResponsiveLayouts) => {
    setLayouts(allLayouts);
    try {
      localStorage.setItem(fullStorageKey, JSON.stringify(allLayouts));
    } catch { /* ignore */ }
  };

  const handleReset = () => {
    localStorage.removeItem(fullStorageKey);
    setLayouts({});
  };

  const generateDefaultLayout = (breakpointCols: number): Layout => {
    return items.map((item, i) => {
      const def = item.defaultLayout || { w: breakpointCols, h: 4 };
      const w = Math.min(def.w, breakpointCols);
      let x = def.x !== undefined ? def.x : (i * 4) % breakpointCols;
      
      // Clamp x first to fit the current breakpoint cols bounds
      x = Math.min(x, breakpointCols - w);
      
      // Mirror x coordinate for RTL
      if (isRtl) {
        x = breakpointCols - w - x;
      }

      const l: LayoutItem = {
        i: item.id,
        x: x,
        y: def.y !== undefined ? def.y : Math.floor(i / (breakpointCols / 4)) * def.h,
        w: w,
        h: def.h,
        minW: def.minW || 2,
        minH: def.minH || 2,
      };
      return l;
    });
  };

  const mergedLayouts: ResponsiveLayouts = { ...layouts };
  Object.keys(cols).forEach((bp) => {
    const breakpointCols = cols[bp as keyof typeof cols];
    if (!mergedLayouts[bp] || mergedLayouts[bp].length !== items.length) {
      const existing = mergedLayouts[bp] || [];
      const newLayout = generateDefaultLayout(breakpointCols).map((l: LayoutItem) => {
        const found = existing.find((e: LayoutItem) => e.i === l.i);
        return found ? found : l;
      });
      mergedLayouts[bp] = newLayout;
    }
  });

  if (!mounted) {
    return <div className="animate-pulse flex-1 bg-[var(--app-bg)] opacity-50" />;
  }

  return (
    <div className={cn('react-grid-wrapper -mx-2 flex flex-col', className)}>
      
      {/* ── Header: Reset Layout ── */}
      <div className="flex justify-end px-2 mb-2">
        <button 
          onClick={handleReset} 
          className="flex items-center gap-1 text-xs font-medium text-[var(--text-soft)] hover:text-[var(--text-primary)] transition-colors opacity-60 hover:opacity-100"
        >
          <IconLayoutDashboard size={12} />
          {t.displaySettings?.resetLayout ?? 'Reset default layout'}
        </button>
      </div>

      <style>{`
        /* Tesla Grid Overrides */
        .react-grid-item {
          transition: all 200ms cubic-bezier(0.165, 0.84, 0.44, 1);
          transition-property: left, top, right, bottom;
          left: 0;
          right: auto;
        }
        .react-grid-item.cssTransforms {
          transition-property: transform;
          left: 0;
          right: auto;
        }
        .react-grid-item.resizing {
          z-index: 100;
          will-change: width, height;
        }
        
        /* Dragging state: flat design with Electric Blue border outline */
        .react-grid-item.react-draggable-dragging {
          transition: none;
          z-index: 100;
          will-change: transform;
          box-shadow: none !important;
          border: 2px solid var(--brand) !important;
          border-radius: var(--radius) !important;
          opacity: 0.95;
        }

        /* Show the visual Electric Blue grid behind the container when dragging */
        .react-grid-layout.is-dragging {
          background-image: linear-gradient(to right, rgba(62, 106, 225, 0.05) 1px, transparent 1px),
                            linear-gradient(to bottom, rgba(62, 106, 225, 0.05) 1px, transparent 1px);
          background-size: calc((100% - 220px) / 12 + 20px) ${rowHeight + 20}px;
          background-position: left top;
        }

        /* Resize Handle */
        .react-grid-item > .react-resizable-handle {
          position: absolute;
          width: 20px;
          height: 20px;
          bottom: 0;
          right: 0;
          cursor: se-resize;
          z-index: 20;
          opacity: 0;
          transition: opacity 0.2s ease-in-out;
          background-image: none !important;
        }
        .react-grid-item:hover > .react-resizable-handle {
          opacity: 1;
        }
        .react-grid-item > .react-resizable-handle::after {
          content: "";
          position: absolute;
          right: 6px;
          bottom: 6px;
          width: 8px;
          height: 8px;
          border-right: 2px solid var(--text-soft, #8E8E8E);
          border-bottom: 2px solid var(--text-soft, #8E8E8E);
          border-bottom-right-radius: 1px;
        }
        .react-grid-item > .react-resizable-handle::before {
          content: "";
          position: absolute;
          right: 10px;
          bottom: 10px;
          width: 8px;
          height: 8px;
          border-right: 2px solid var(--text-soft, #8E8E8E);
          border-bottom: 2px solid var(--text-soft, #8E8E8E);
        }

        /* Drop Placeholder: Electric Blue dashed outline with light fill */
        .react-grid-placeholder {
          background: var(--brand) !important;
          opacity: 0.05 !important;
          border: 2px dashed var(--brand) !important;
          border-radius: var(--radius);
          transition-duration: 150ms;
          z-index: 2;
        }
      `}</style>
      <ResponsiveGridLayout
        className={cn('layout', { 'is-dragging': isDragging })}
        layouts={mergedLayouts}
        breakpoints={{ lg: 1200, md: 996, sm: 768, xs: 480, xxs: 0 }}
        cols={cols}
        rowHeight={rowHeight}
        onLayoutChange={handleLayoutChange}
        onDragStart={() => setIsDragging(true)}
        onDragStop={() => setIsDragging(false)}
        onResizeStart={() => setIsDragging(true)}
        onResizeStop={() => setIsDragging(false)}
        draggableHandle=".react-grid-dragHandle"
        margin={[20, 20]} // Cloudscape 20px standard container spacing
        containerPadding={[0, 0]}
        useCSSTransforms={true}
        isBounded={true}
        isDroppable={true}
      >
        {items.map((item) => (
          <div
            key={item.id}
            className={cn('relative group h-full flex flex-col', item.className)}
          >
            <DragHandleIcon />
            <div className="w-full h-full [&>div]:h-full [&>div]:m-0">
              {item.children}
            </div>
          </div>
        ))}
      </ResponsiveGridLayout>
    </div>
  );
}


