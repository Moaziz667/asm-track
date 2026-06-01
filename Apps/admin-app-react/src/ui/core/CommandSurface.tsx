
import * as React from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { IconSearch } from '@tabler/icons-react';

type CommandItem = {
  label: string;
  category: string;
  action: () => void;
};

export function CommandSurface() {
  const router = useRouter();
  const [opened, setOpened] = React.useState(false);
  const [search, setSearch] = React.useState('');
  const [activeIndex, setActiveIndex] = React.useState(0);

  // Core system commands catalog
  const COMMANDS = React.useMemo<CommandItem[]>(() => [
    { label: "Aller au Tableau de bord", category: "Navigation", action: () => router('/dashboard') },
    { label: "Aller au Dispatch Desk", category: "Navigation", action: () => router('/dispatch-desk') },
    { label: "Aller au Suivi des livraisons", category: "Navigation", action: () => router('/deliveries') },
    { label: "Aller à la Planification de tournée", category: "Navigation", action: () => router('/route-builder') },
    { label: "Aller aux Paramètres système", category: "Navigation", action: () => router('/settings') },
    { label: "Importer des commandes depuis Odoo", category: "Opérations", action: () => router('/import') },
    { label: "Consulter les anomalies (échecs/retours)", category: "Opérations", action: () => router('/deliveries?status=FAILED') },
  ], [router]);

  // Filter commands in place
  const filteredCommands = React.useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return COMMANDS;
    return COMMANDS.filter(cmd => 
      cmd.label.toLowerCase().includes(q) || 
      cmd.category.toLowerCase().includes(q)
    );
  }, [search, COMMANDS]);

  // Adjust active suggestion selection on content list updates
  React.useEffect(() => {
    setActiveIndex(0);
  }, [filteredCommands]);

  // Keyboard shortcut listener
  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setOpened(prev => !prev);
        setSearch('');
      }

      if (!opened) return;

      if (e.key === 'Escape') {
        e.preventDefault();
        setOpened(false);
      }

      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActiveIndex(prev => (prev + 1) % Math.max(1, filteredCommands.length));
      }

      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActiveIndex(prev => (prev - 1 + filteredCommands.length) % Math.max(1, filteredCommands.length));
      }

      if (e.key === 'Enter') {
        e.preventDefault();
        const selected = filteredCommands[activeIndex];
        if (selected) {
          selected.action();
          setOpened(false);
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [opened, filteredCommands, activeIndex]);

  if (!opened) return null;

  return (
    <div className="fixed inset-0 z-[9999] flex items-start justify-center pt-24 select-none">
      {/* Backdrop overlay */}
      <div
        className="fixed inset-0 bg-black/40 backdrop-blur-[1.5px] transition-opacity z-[9998]"
        onClick={() => setOpened(false)}
      />

      {/* Floating search surface */}
      <div className="relative w-full max-w-[500px] border border-[var(--border-grid)] bg-[var(--bg-panel)] rounded-dense-md shadow-2xl flex flex-col overflow-hidden max-h-[350px]">
        {/* Search bar input container */}
        <div className="h-10 border-b border-[var(--border-grid)] px-dense-4 flex items-center gap-dense-2 bg-[var(--bg-canvas)]/30 shrink-0">
          <IconSearch size={14} className="text-[var(--text-muted)]" />
          <input
            type="text"
            autoFocus
            placeholder="Rechercher une action ou page... (⌘K)"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="flex-1 bg-transparent text-xs font-semibold text-[var(--text-primary)] placeholder:text-[var(--text-muted)]/60 outline-none h-full w-full"
          />
        </div>

        {/* Suggestion list */}
        <div className="flex-1 min-h-0 overflow-y-auto py-dense-2">
          {filteredCommands.length === 0 ? (
            <div className="px-dense-4 py-dense-3 text-center text-[11px] font-semibold text-[var(--text-muted)] uppercase tracking-wide">
              Aucun résultat trouvé
            </div>
          ) : (
            filteredCommands.map((cmd, i) => (
              <div
                key={cmd.label}
                onClick={() => { cmd.action(); setOpened(false); }}
                className={cn(
                  'px-dense-4 py-2 flex items-center justify-between cursor-pointer transition-colors',
                  i === activeIndex 
                    ? 'bg-state-active/10 text-state-active' 
                    : 'text-[var(--text-primary)] hover:bg-[var(--bg-canvas)]'
                )}
              >
                <span className="text-xs font-semibold">{cmd.label}</span>
                <span className={cn(
                  'text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-dense-sm border',
                  i === activeIndex 
                    ? 'bg-state-active/15 border-state-active/30 text-state-active' 
                    : 'bg-neutral-800/10 border-[var(--border-grid)] text-[var(--text-muted)]'
                )}>
                  {cmd.category}
                </span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

