'use client';

import { Link } from 'react-router-dom';
import { IconRoute } from '@tabler/icons-react';

interface Props {
  routeId?: string;
  routeName: string;
  count: number;
}

export function RouteGroupHeader({ routeId, routeName, count }: Props) {
  return (
    <tr style={{ background: 'var(--surface)' }}>
      <td colSpan={9} style={{ padding: '4px 14px' }}>
        <div className="flex items-center gap-1.5">
          <IconRoute size={11} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
          {routeId ? (
            <Link to={`/routes/${routeId}`} style={{ textDecoration: 'none' }}>
              <span className="text-[11px] font-[500]" style={{ color: 'var(--text-primary)' }}>
                {routeName}
              </span>
            </Link>
          ) : (
            <span className="text-[11px] font-[500]" style={{ color: 'var(--text-muted)' }}>
              {routeName}
            </span>
          )}
          <span className="text-[10px]" style={{ color: 'var(--text-muted)' }}>· {count}</span>
        </div>
      </td>
    </tr>
  );
}
