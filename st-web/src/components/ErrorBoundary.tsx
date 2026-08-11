import { Component, type ErrorInfo, type ReactNode } from 'react';
import { AlertTriangle } from 'lucide-react';

interface Props {
  children: ReactNode;
}
interface State {
  hasError: boolean;
  error: Error | null;
}

export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('ErrorBoundary caught:', error, errorInfo);
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center h-screen bg-surface-2 px-6 text-center">
          <div className="w-16 h-16 rounded-2xl bg-red-500/15 flex items-center justify-center mb-6">
            <AlertTriangle className="w-8 h-8 text-red-500" aria-hidden />
          </div>
          <h1 className="text-xl font-semibold text-fg mb-2">页面出错了</h1>
          <p className="text-sm text-muted mb-6 max-w-md">
            抱歉，页面渲染时发生错误。请尝试刷新页面，若问题持续请联系管理员。
          </p>
          <div className="flex gap-3">
            <button type="button" onClick={this.handleReset} className="btn-secondary">
              重试
            </button>
            <button type="button" onClick={() => window.location.reload()} className="btn-primary">
              刷新页面
            </button>
          </div>
          {this.state.error && (
            <pre className="mt-6 max-w-2xl w-full text-left text-xs text-muted bg-surface border border-border rounded-lg p-4 overflow-auto">
              {this.state.error.message}
            </pre>
          )}
        </div>
      );
    }
    return this.props.children;
  }
}