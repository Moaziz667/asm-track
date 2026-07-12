import { ReactNode } from 'react';
import ContentBreadcrumb from './ContentBreadcrumb';

type PageHeaderProps = {
  children?: ReactNode;
  className?: string;
};

export default function PageHeader({ children, className }: PageHeaderProps) {
  return (
    <div className={className}>
      {/* Breadcrumb — aligned with content grid */}
      <div className="mb-6">
        <ContentBreadcrumb />
      </div>
      
      {children}
    </div>
  );
}