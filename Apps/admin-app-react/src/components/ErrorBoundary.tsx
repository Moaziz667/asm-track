import React, { Component, ErrorInfo, ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { IconShieldCheck, IconX } from '@tabler/icons-react';
import { getCopy } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';

interface Props {
  children?: ReactNode;
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null
  };

  public static getDerivedStateFromError(error: Error): State {
    // Update state so the next render will show the fallback UI.
    return { hasError: true, error };
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('[ErrorBoundary] Uncaught component crash details:', error, errorInfo);
  }

  private handleReset = () => {
    this.setState({ hasError: false, error: null });
    window.location.reload();
  };

  public render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }

      const t = getCopy(useLocaleStore.getState().locale || 'fr');
      return (
        <div className="flex flex-col items-center justify-center p-10 min-h-[400px] text-center bg-[var(--surface)] border border-[var(--border)] rounded-[2px] animate-fade-in max-w-[600px] mx-auto my-10">
          <div className="w-12 h-12 rounded-full flex items-center justify-center bg-red-50 text-red-600 mb-4 shadow-sm border border-red-100">
            <IconX size={24} />
          </div>
          <h2 className="text-md font-bold text-[var(--text-primary)] uppercase tracking-tight mb-2">
            {t.errorBoundary?.title}
          </h2>
          <p className="text-xs text-[var(--text-muted)] max-w-sm mb-6 leading-relaxed">
            {t.errorBoundary?.description}
          </p>
          {this.state.error && (
            <div className="w-full text-left p-4 mb-6 rounded-[2px] border border-[var(--border)] bg-[var(--app-bg)] max-h-[150px] overflow-auto">
              <p className="text-2xs font-mono text-red-500 font-bold leading-tight">
                {this.state.error.name}: {this.state.error.message}
              </p>
            </div>
          )}
          <div className="flex gap-2">
            <Button size="sm" onClick={this.handleReset} className="rounded-[2px]">
              {t.errorBoundary?.resetButton}
            </Button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
export default ErrorBoundary;
