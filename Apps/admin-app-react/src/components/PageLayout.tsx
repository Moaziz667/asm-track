import { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import ContentBreadcrumb from './ContentBreadcrumb';

type PageLayoutProps = {
  children: ReactNode;
  className?: string;
  /** Show the integrated breadcrumb at the top */
  showBreadcrumb?: boolean;
  /** Additional padding top (default: 32px) */
  paddingTop?: string;
};

export default function PageLayout({ 
  children, 
  className, 
  showBreadcrumb = true,
  paddingTop = '32px'
}: PageLayoutProps) {
  return (
    <div 
      className={cn('min-h-full', className)}
      style={{ padding: `${paddingTop} 32px 32px 32px` }}
    >
      {/* Integrated breadcrumb — no border, no background, just inline text */}
      {showBreadcrumb && (
        <div className="mb-6">
          <ContentBreadcrumb />
        </div>
      )}
      
      {children}
    </div>
  );
}