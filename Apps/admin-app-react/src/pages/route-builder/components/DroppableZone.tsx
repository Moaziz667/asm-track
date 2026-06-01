'use client';

import { ReactNode } from 'react';
import { useDroppable } from '@dnd-kit/core';

interface DroppableZoneProps {
  id: string;
  children: ReactNode;
  className?: string;
}

export function DroppableZone({ id, children, className }: DroppableZoneProps) {
  const { setNodeRef, isOver } = useDroppable({ id });
  return (
    <div
      ref={setNodeRef}
      className={[className, isOver ? 'ring-2 ring-inset ring-blue-400 rounded' : ''].filter(Boolean).join(' ')}
    >
      {children}
    </div>
  );
}
